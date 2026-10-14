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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.lineage.LineageCacheConfig;

/** One immutable native universe, exact optimization, and transactional projection. */
public final class FederatedPlanMemo extends AFederatedPlanner {
	public record Receipt(MemoProgramFacts facts,MemoProgramModel.Solution solution)
		implements PlannerInvocationReceipt { }
	private record Projection(Hop hop,ExecType execution,FederatedOutput output) { }
	private record Before(Hop hop,ExecType forced,ExecType execution,FederatedOutput output,
		boolean derived,boolean prefetch,boolean selected,Lop lops) {
		Before(Hop hop) { this(hop,hop.getForcedExecType(),hop.getExecType(),hop.getFederatedOutput(),
			hop.isFederatedOutputDerived(),hop.prefetchActivated(),hop.isPlannerPlacementSelected(),hop.getLops()); }
		void restore() {
			hop.setForcedExecType(forced); hop.setExecType(execution); hop.setFederatedOutput(output);
			hop.setFederatedOutputDerived(derived); hop.setPlannerPlacementSelected(selected); hop.setLops(lops);
			if(prefetch) hop.activatePrefetch(); else hop.deactivatePrefetch();
		}
	}
	private final CanonicalWorkerPool pool;
	private final MemoOptimizer.Limits limits;
	public FederatedPlanMemo() { this(null,MemoOptimizer.Limits.exactDefaults()); }
	public FederatedPlanMemo(CanonicalWorkerPool pool,MemoOptimizer.Limits limits) {
		this.pool = pool; this.limits = limits;
	}
	@Override public void rewriteProgram(DMLProgram program,FunctionCallGraph graph,FunctionCallSizeInfo sizes) {
		plan(program);
	}
	public Receipt plan(DMLProgram program) {
		MemoPlanRegistry.beginPlanning(program);
		try { return planWithLease(program); }
		finally { MemoPlanRegistry.endPlanning(program); }
	}
	private Receipt planWithLease(DMLProgram program) {
		// A cached function return bypasses the selected body and its planned supplies.
		// ADR19 excludes that execution contract; never silently change user settings.
		if(DMLScript.LINEAGE_REUSE != null && DMLScript.LINEAGE_REUSE.isMultilevelReuse()
			|| LineageCacheConfig.getCacheType() != null && LineageCacheConfig.getCacheType().isMultilevelReuse())
			throw new IllegalStateException("MEMO_UNSUPPORTED_FUNCTION_OUTPUT_REUSE: disable multilevel lineage reuse");
		long started = System.nanoTime();
		MemoProgramFacts facts = MemoProgramFacts.analyze(program); facts.requireComplete();
		MemoSources sources = MemoSources.acquire(facts,pool);
		long factsFinished = System.nanoTime();
		tracePhase("facts",factsFinished-started);
		MemoNativeRules oracle = new MemoNativeRules();
		MemoRuleBuilder.Result universe = MemoRuleBuilder.build(facts,sources,oracle);
		long rulesFinished = System.nanoTime();
		tracePhase("rules",rulesFinished-factsFinished);
		MemoProgramModel model = new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
		long modelFinished = System.nanoTime();
		tracePhase("model",modelFinished-rulesFinished);
		MemoProgramModel.Solution solution = model.optimize(limits);
		long optimizationFinished = System.nanoTime();
		tracePhase("optimization",optimizationFinished-modelFinished);
		if(solution.optimization().status() != MemoOptimizer.Status.OPTIMAL)
			throw new IllegalStateException("Memo planner did not prove an optimum: "
				+ solution.optimization().status() + " " + solution.diagnostics());
		int[] selected = solution.optimization().selectedRules();
		// All derived contracts are validated before any compiled Hop or registry is changed.
		var loops = model.loopContracts(selected);
		List<Projection> projections = new ArrayList<>();
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) {
			Hop hop = facts.compiledOwnerHop(owner);
			if(hop instanceof FunctionOp function && function.getFunctionType() == FunctionOp.FunctionType.DML) {
				projections.add(new Projection(hop,ExecType.CP,FederatedOutput.NONE)); continue;
			}
			List<RuleMemo.Rule> rules = universe.memo().rules(owner);
			if(!rules.isEmpty()) {
				RuleMemo.Rule rule = rules.get(selected[owner]);
				ExecType physical = rule.exec();
				projections.add(new Projection(hop,physical,rule.output().federated() ? FederatedOutput.FOUT : FederatedOutput.LOUT));
				if(hop instanceof FunctionOp function
					&& function.getFunctionType()==FunctionOp.FunctionType.MULTIRETURN_BUILTIN
					&& function.getOutputs()!=null) {
					if(function.getOutputs().size()!=rule.outputs().size())
						throw new IllegalStateException("Selected multi-return rule has stale output arity: "
							+function.getFunctionKey());
					for(int slot=0;slot<function.getOutputs().size();slot++) {
						Hop output=function.getOutputs().get(slot);
						MemoPartition layout=rule.outputs().get(slot);
						projections.add(new Projection(output,physical,
							layout.federated() ? FederatedOutput.FOUT : FederatedOutput.LOUT));
					}
				}
			}
			else {
				Set<MemoPartition> layouts = model.selectedLayouts(owner,selected);
				if(layouts.isEmpty()) continue; // an uncalled compiled function template
				boolean allFed = layouts.stream().allMatch(MemoPartition::federated);
				boolean allLocal = layouts.equals(Set.of(MemoPartition.LOCAL));
				// Mixed guarded aliases carry the actual object. Only the selected use edge converts it.
				projections.add(new Projection(hop,allFed ? ExecType.FED : ExecType.CP,
					allFed ? FederatedOutput.FOUT : allLocal ? FederatedOutput.LOUT : FederatedOutput.NONE));
			}
		}
		List<Before> before = projections.stream().map(p -> new Before(p.hop())).toList();
		Map<Long,MemoPlanRegistry.CompilerState> originals=new java.util.LinkedHashMap<>();
		for(Projection projection:projections) originals.putIfAbsent(projection.hop().getHopID(),
			MemoPlanRegistry.originalState(program,projection.hop()));
		var oldSupplies = MemoSupplyRegistry.snapshot(program); var oldLoops = MemoLoopRuntime.snapshot(program);
		try {
			MemoLoopRuntime.replaceForOwner(program,loops);
			for(Projection projection : projections) {
				Hop hop = projection.hop(); hop.setForcedExecType(projection.execution()); hop.resetExecType();
				hop.setFederatedOutput(projection.output()); hop.deactivatePrefetch();
				hop.setPlannerPlacementSelected(true); hop.setLops(null);
			}
			MemoPlanRegistry.publish(program,facts,universe.memo(),selected,universe.shapes(),solution.supplies(),
				epoch -> model.supplySchedule(epoch,solution),originals);
		}
		catch(RuntimeException failure) {
			for(Before state : before) state.restore();
			MemoSupplyRegistry.replaceForOwner(program,oldSupplies);
			MemoLoopRuntime.replaceForOwner(program,oldLoops);
			throw failure;
		}
		if(Boolean.getBoolean("sysds.fedplanner.memo.trace")) {
			long generated = java.util.stream.IntStream.range(0,universe.memo().ownerCount())
				.mapToLong(owner -> universe.memo().rules(owner).size()).sum();
			long federated = projections.stream().filter(p -> p.execution() == ExecType.FED).count();
			var stats = solution.optimization().statistics();
			System.err.println("[MemoPlanner] status=OPTIMAL epoch=" + MemoPlanRegistry.epoch(program)
				+ " workers=" + sources.workers() + " nativeRules=" + generated + " fedOwners=" + federated
				+ " supplies=" + solution.supplies().values().stream().mapToInt(List::size).sum()
				+ " nodes=" + stats.nodes() + " checks=" + stats.checkEvaluations()
				+ " cacheHits=" + stats.checkCacheHits() + " pruned=" + stats.removedRules()
				+ " supportQueries=" + stats.supportQueries() + " worklistVisits=" + stats.worklistVisits()
				+ " costEvaluations=" + stats.costEvaluations() + " costCacheHits=" + stats.costCacheHits()
				+ " boundPrunes=" + stats.boundPrunes()
				+ " conditionalBoundQueries=" + stats.conditionalBoundQueries()
				+ " conditionalBoundCacheHits=" + stats.conditionalBoundCacheHits()
				+ " wideBoundRelaxations=" + stats.wideBoundRelaxations()
				+ " oracleMisses=" + oracle.cacheStats().misses() + " oracleHits=" + oracle.cacheStats().hits()
				+ " factsNs=" + (factsFinished-started) + " rulesNs=" + (rulesFinished-factsFinished)
				+ " modelNs=" + (modelFinished-rulesFinished)
				+ " optimizationNs=" + (optimizationFinished-modelFinished)
				+ " projectionNs=" + (System.nanoTime()-optimizationFinished)
				+ " cost=" + solution.optimization().cost().toPlainString());
		}
		return new Receipt(facts,solution);
	}
	private static void tracePhase(String phase,long elapsedNanos) {
		if(Boolean.getBoolean("sysds.fedplanner.memo.trace"))
			System.err.println("[MemoPlannerPhase] phase="+phase+" elapsedNs="+elapsedNanos);
	}
	@Override public void rewriteFunctionDynamic(FunctionStatementBlock function,LocalVariableMap arguments) {
		throw new IllegalStateException("Memo function recompilation requires its exact program epoch and call bindings");
	}
}

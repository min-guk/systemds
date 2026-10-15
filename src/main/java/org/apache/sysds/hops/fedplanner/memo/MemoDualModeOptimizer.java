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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;

/** Shared exact kernels with a separate, certified, bounded-memory search route.
 * All search domains contain original ordinals. A pending frame represents every
 * not-yet-created sibling, so no priority queue or fixed frontier cap is needed. */
public final class MemoDualModeOptimizer {
	public enum Mode { GLOBAL, LOCAL }
	public record Options(boolean directExact,long seedWorkLimit,int factorCells,long cacheBytes,long diagnosticNodes,long maxResidentBytes,boolean trace,long directCells,int clusterCells,boolean improveSeed) {
		public Options {
			if(seedWorkLimit<0 || factorCells<1 || cacheBytes<0 || diagnosticNodes<1 || maxResidentBytes<0 || directCells<0 || clusterCells<0)
				throw new IllegalArgumentException("Invalid Memo dual-mode options");
		}
		public Options(boolean directExact,long seedWorkLimit,int factorCells,long cacheBytes,long diagnosticNodes,long maxResidentBytes,boolean trace) {
			this(directExact,seedWorkLimit,factorCells,cacheBytes,diagnosticNodes,maxResidentBytes,trace,2_000_000,4096,false);
		}
		public Options(boolean directExact,long seedWorkLimit,int factorCells,long cacheBytes,long diagnosticNodes,long maxResidentBytes,boolean trace,long directCells) {
			this(directExact,seedWorkLimit,factorCells,cacheBytes,diagnosticNodes,maxResidentBytes,trace,directCells,4096,false);
		}
		public static Options defaults() { return new Options(true,100_000,256,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,false); }
		public Options withDirectExact(boolean value) { return new Options(value,seedWorkLimit,factorCells,cacheBytes,diagnosticNodes,maxResidentBytes,trace,directCells,clusterCells,improveSeed); }
		public Options withSeedWorkLimit(long value) { return new Options(directExact,value,factorCells,cacheBytes,diagnosticNodes,maxResidentBytes,trace,directCells,clusterCells,improveSeed); }
		public Options withFactorCells(int value) { return new Options(directExact,seedWorkLimit,value,cacheBytes,diagnosticNodes,maxResidentBytes,trace,directCells,clusterCells,improveSeed); }
		public Options withDiagnosticNodes(long value) { return new Options(directExact,seedWorkLimit,factorCells,cacheBytes,value,maxResidentBytes,trace,directCells,clusterCells,improveSeed); }
		public Options withMaxResidentBytes(long value) { return new Options(directExact,seedWorkLimit,factorCells,cacheBytes,diagnosticNodes,value,trace,directCells,clusterCells,improveSeed); }
		public Options withTrace(boolean value) { return new Options(directExact,seedWorkLimit,factorCells,cacheBytes,diagnosticNodes,maxResidentBytes,value,directCells,clusterCells,improveSeed); }
		public Options withDirectCells(long value) { return new Options(directExact,seedWorkLimit,factorCells,cacheBytes,diagnosticNodes,maxResidentBytes,trace,value,clusterCells,improveSeed); }
		public Options withClusterCells(int value) { return new Options(directExact,seedWorkLimit,factorCells,cacheBytes,diagnosticNodes,maxResidentBytes,trace,directCells,value,improveSeed); }
		public Options withImproveSeed(boolean value) { return new Options(directExact,seedWorkLimit,factorCells,cacheBytes,diagnosticNodes,maxResidentBytes,trace,directCells,clusterCells,value); }
	}
	public record Statistics(long directExactSolves,long directAttempts,long seedAttempts,long seedEvaluations,
		long repairCalls,long nodes,long relaxationCalls,long boundCacheHits,long exactCells,long pruned,
		long maxPending,long peakEstimatedBytes,long cacheBytes,long kernelNanos,long seedNanos,long boundNanos,
		long searchNanos,long setupNanos,long validationNanos,long firstUpperNanos,long bestUpperNanos,
		long certificateNanos,long clusterGroups,long clusterCells,long clusterQueries,long clusterCacheHits,
		long clusterBytes,long clusterBuildNanos,long clusterQueryNanos,String route) { }
	public record Progress(long nodes,BigDecimal lowerBound,BigDecimal upperBound) { }
	public record Result(Status status,BigDecimal lowerBound,BigDecimal upperBound,int[] selectedRules,
		Statistics statistics,List<Progress> progression,String reason,MemoOptimizer.Statistics kernelStatistics) {
		public Result {
			selectedRules=selectedRules==null ? null : selectedRules.clone();
			progression=List.copyOf(progression);
		}
		@Override public int[] selectedRules() { return selectedRules==null ? null : selectedRules.clone(); }
		public boolean certified() { return status==Status.OPTIMAL || status==Status.EPSILON_OPTIMAL; }
	}
	private static final BigDecimal EPSILON_FACTOR=new BigDecimal("1.05");
	private final int[] sizes;
	private final boolean[] coverage;
	private final double[][] nativeInput;
	private BigDecimal[][] nativeCosts;
	private final List<Check> checks;
	private final List<CostTerm> costs;
	private final int[][] checkScopes,costScopes;
	private int[][][] costClasses;
	private final int[] degree;
	private final Mode mode;
	private final Options options;
	private final MemoResourcePolicy resources;
	private long baseBytes;
	private MemoClusterBounds clusters;
	private final long startedNanos=System.nanoTime();
	private long setupNanos,validationNanos,firstUpperNanos=-1,bestUpperNanos=-1;
	private final LinkedHashMap<BoundKey,BigDecimal> bounds=new LinkedHashMap<>(16,.75f,true);
	private final ArrayDeque<Frame> pending=new ArrayDeque<>();
	private final List<Progress> history=new ArrayList<>();
	private Consumer<Progress> listener;
	private BigDecimal incumbent,unknownLower,lastLower,lastUpper;
	private int[] selected;
	private boolean optimized;
	private long directExactSolves,directAttempts,seedAttempts,seedEvaluations,repairCalls,nodes,
		relaxationCalls,boundCacheHits,exactCells,pruned,maxPending,cacheBytes,pendingBytes,historyBytes,reservedBytes,
		kernelNanos,seedNanos,boundNanos,searchNanos,searchStarted;
	private String route="DIRECT";
	private MemoOptimizer.Statistics kernelStatistics=emptyKernelStatistics();

	public MemoDualModeOptimizer(int[] sizes,boolean[] coverage,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> costs,Mode mode,Options options) {
		this.sizes=sizes.clone(); this.coverage=coverage.clone();
		this.mode=Objects.requireNonNull(mode); this.options=Objects.requireNonNull(options);
		this.checks=List.copyOf(checks); this.costs=List.copyOf(costs);
		if(sizes.length!=coverage.length || sizes.length!=nativeCosts.length)
			throw new IllegalArgumentException("Memo input dimensions differ");
		nativeInput=new double[sizes.length][];
		for(int owner=0;owner<sizes.length;owner++) {
			if(sizes[owner]<0 || nativeCosts[owner].length!=sizes[owner])
				throw new IllegalArgumentException("Invalid immutable rule domain");
			nativeInput[owner]=nativeCosts[owner].clone();
			for(int rule=0;rule<sizes[owner];rule++) {
				if(!Double.isFinite(nativeCosts[owner][rule])) throw new IllegalArgumentException("Nonfinite native cost");
			}
		}
		checkScopes=checks.stream().map(Check::owners).toArray(int[][]::new);
		costScopes=costs.stream().map(CostTerm::owners).toArray(int[][]::new);
		degree=new int[sizes.length];
		for(int[] scope:checkScopes) validateScope(scope);
		for(int[] scope:costScopes) validateScope(scope);
		resources=options.maxResidentBytes()==Long.MAX_VALUE ? MemoResourcePolicy.defaults()
			: new MemoResourcePolicy(options.maxResidentBytes());
		baseBytes=estimateBaseBytes();
	}
	/** Stream production checkpoints; trace=true additionally retains them for small oracle tests. */
	public MemoDualModeOptimizer onProgress(Consumer<Progress> listener) {
		if(optimized) throw new IllegalStateException("Invocation already consumed");
		this.listener=Objects.requireNonNull(listener); return this;
	}
	private void validateScope(int[] scope) {
		for(int owner:scope) {
			if(owner<0 || owner>=sizes.length) throw new IllegalArgumentException("Unknown owner "+owner);
			degree[owner]++;
		}
	}
	public Result optimize() {
		if(optimized) throw new IllegalStateException("A dual-mode invocation is single-use");
		optimized=true;
		for(boolean complete:coverage) if(!complete) return finish(Status.UNRESOLVED,null,"Incomplete candidate coverage");
		for(int size:sizes) if(size==0) return finish(Status.INFEASIBLE,null,"Empty original domain");
		long domainBytes=16L+8L*sizes.length;
		for(int size:sizes) domainBytes+=48L+8L*((size+63L)/64L);
		if(!resources.admit(workingBytes(),domainBytes))
			return finish(Status.RESOURCE_EXHAUSTED,null,"Insufficient minimum workspace");
		BitSet[] domains=initialDomains();
		reservedBytes=Frame.estimateBytes(domains);
		if(!resources.admit(residentBytes(),0))
			return finish(Status.RESOURCE_EXHAUSTED,null,"Cannot retain initial proof domains");
		if(options.directExact()) {
			MemoOptimizer.DirectResult direct=direct(domains);
			if(direct.completed()) return completedDirect(direct);
			domains=direct.domains();
		}
		route="CERTIFIED_SEARCH";
		long setupStarted=System.nanoTime();
		long additional=0;
		for(int size:sizes) additional=MemoResourcePolicy.checkedAdd(additional,48L+768L*size);
		for(int[] scope:costScopes) for(int owner:scope)
			additional=MemoResourcePolicy.checkedAdd(additional,24L+4L*sizes[owner]);
		if(!resources.admit(residentBytes(),additional))
			return finish(Status.RESOURCE_EXHAUSTED,null,"Cannot prepare search costs and class maps");
		nativeCosts=new BigDecimal[sizes.length][];
		Map<Long,BigDecimal> exactValues=new java.util.HashMap<>();
		for(int owner=0;owner<sizes.length;owner++) {
			nativeCosts[owner]=new BigDecimal[sizes[owner]];
			for(int rule=0;rule<sizes[owner];rule++)
				nativeCosts[owner][rule]=exactValues.computeIfAbsent(Double.doubleToLongBits(nativeInput[owner][rule]),
					bits -> new BigDecimal(Double.longBitsToDouble(bits)));
		}
		costClasses=new int[costs.size()][][];
		for(int id=0;id<costs.size();id++) {
			costClasses[id]=costs.get(id).certifiedCostClasses();
			if(costClasses[id]!=null) for(int axis=0;axis<costScopes[id].length;axis++)
				if(costClasses[id][axis].length!=sizes[costScopes[id][axis]])
					throw new IllegalArgumentException("Cost class domain mismatch");
		}
		baseBytes=estimateBaseBytes();
		if(options.clusterCells()>0)
			clusters=new MemoClusterBounds(sizes,nativeCosts,checks,costs,domains,options.clusterCells(),
				resources.withResidentBytes(residentBytes()));
		setupNanos+=System.nanoTime()-setupStarted;
		BigDecimal rootBound=bound(domains);
		Frame root=new Frame(domains,rootBound,true);
		reservedBytes=0;
		push(root); publish(rootBound);
		if(options.seedWorkLimit()>0) {
			long started=System.nanoTime(); seedAttempts++;
			MemoFeasibleSeed.Result seed=MemoFeasibleSeed.find(sizes,nativeInput,checks,costs,domains,
				options.seedWorkLimit(),resources.withResidentBytes(residentBytes()));
			seedNanos+=System.nanoTime()-started; seedEvaluations+=seed.evaluations(); repairCalls+=seed.repairs();
			if(seed.selectedRules()!=null) {
				consider(seed.selectedRules()); publish(globalLower());
				if(options.improveSeed() && clusters!=null && certificate(globalLower())==null) {
					long improvementStarted=System.nanoTime();
					MemoFeasibleSeed.Result improved=MemoFeasibleSeed.improve(sizes,nativeInput,checks,costs,domains,
						selected,clusters.scopes(),Math.max(0,options.seedWorkLimit()-seed.evaluations()),
						resources.withResidentBytes(residentBytes()));
					seedNanos+=System.nanoTime()-improvementStarted;
					seedEvaluations+=improved.evaluations(); repairCalls+=improved.repairs();
					if(improved.selectedRules()!=null) consider(improved.selectedRules());
				}
			}
			if(seed.fullProof()!=null && seed.fullProof().status()==Status.OPTIMAL) {
				consider(seed.fullProof().selectedRules()); pending.clear(); pendingBytes=0;
				return finish(Status.OPTIMAL,incumbent,"Seed repair completed the full exact problem");
			}
			if(seed.fullProof()!=null && seed.fullProof().status()==Status.INFEASIBLE) {
				pending.clear(); pendingBytes=0; return finish(Status.INFEASIBLE,null,"Full-domain repair proved infeasibility");
			}
		}
		searchStarted=System.nanoTime();
		try {
			while(true) {
				BigDecimal lower=globalLower(); publish(lower);
				Status certified=certificate(lower);
				if(certified!=null) return finish(certified,lower,"Requested bound certificate established");
				if(pending.isEmpty()) return finish(unknownLower!=null ? Status.UNRESOLVED
					: incumbent==null ? Status.INFEASIBLE : Status.OPTIMAL,lower,
					unknownLower!=null ? "Legality UNKNOWN prevents certification" : "Original cover exhausted");
				Frame frame=pending.peek();
				if(prunable(frame)) { pop(); pruned++; continue; }
				if(frame.branch<0) {
					if(nodes>=options.diagnosticNodes()) return finish(Status.RESOURCE_EXHAUSTED,lower,"Explicit diagnostic node cap");
					nodes++;
					int[] assignment=partial(frame.domains);
					if(truth(assignment)==Truth.NO) { pop(); pruned++; continue; }
					if(fixedUnknown(assignment)) {
						unknownLower=min(unknownLower,frame.lower); pop(); continue;
					}
					if(options.directExact() && !frame.kernelTried) {
						frame.kernelTried=true;
						MemoOptimizer.DirectResult direct=direct(frame.domains);
						if(direct.completed() && direct.result().status()==Status.OPTIMAL) {
							consider(direct.result().selectedRules()); pop(); continue;
						}
						if(direct.completed() && direct.result().status()==Status.INFEASIBLE) { pop(); continue; }
						if(direct.completed() && direct.result().status()==Status.UNRESOLVED) {
							unknownLower=min(unknownLower,frame.lower); pop(); continue;
						}
						// Propagated domains are safe original-model restrictions, never seed restrictions.
						frame.domains=direct.domains(); frame.lower=frame.lower.max(bound(frame.domains));
					}
					if(complete(assignment)) {
						if(truth(assignment)==Truth.YES) consider(assignment);
						else unknownLower=min(unknownLower,frame.lower);
						pop(); continue;
					}
					consider(preferred(frame.domains));
					frame.branch=branch(frame.domains);
					if(frame.branch<0) { // Propagation may have made the node singleton.
						assignment=partial(frame.domains);
						if(truth(assignment)==Truth.YES) consider(assignment);
						else if(truth(assignment)==Truth.UNKNOWN) unknownLower=min(unknownLower,frame.lower);
						pop(); continue;
					}
				}
				// Allocate both replacement descriptions before changing the parent's cover.
				long reservation=frame.bytes+128L+16L*sizes.length;
				if(!admit(reservation))
					return finish(Status.RESOURCE_EXHAUSTED,globalLower(),"Cannot retain DFS child and pending siblings");
				// Bound-cache insertions must not consume the child's admitted workspace.
				reservedBytes+=reservation;
				int owner=frame.branch,rule=preferredRule(owner,frame.domains[owner]);
				BitSet[] childDomains=frame.domains.clone(); childDomains[owner]=new BitSet(sizes[owner]); childDomains[owner].set(rule);
				BitSet[] siblings=frame.domains.clone(); siblings[owner]=(BitSet)siblings[owner].clone(); siblings[owner].clear(rule);
				BigDecimal childBound=frame.lower.max(bound(childDomains));
				BigDecimal siblingBound=siblings[owner].isEmpty() ? null : frame.lower.max(bound(siblings));
				Frame child=new Frame(childDomains,childBound,false);
				if(siblingBound==null) pop();
				else { frame.domains=siblings; frame.lower=siblingBound; }
				reservedBytes-=reservation;
				push(child);
			}
		}
		finally { searchNanos+=System.nanoTime()-searchStarted; searchStarted=0; }
	}
	private MemoOptimizer.DirectResult direct(BitSet[] domains) {
		long started=System.nanoTime(); directAttempts++;
		MemoOptimizer.DirectResult result=new MemoOptimizer(sizes,coverage,nativeInput,checks,costs,
			MemoOptimizer.Limits.exactDefaults()).optimizeDirect(domains,
				resources.withResidentBytes(residentBytes()),options.directCells());
		kernelNanos+=System.nanoTime()-started; kernelStatistics=result.result().statistics();
		exactCells+=kernelStatistics.eliminationCells();
		if(result.completed() && result.result().status()==Status.OPTIMAL) directExactSolves++;
		return result;
	}
	private Result completedDirect(MemoOptimizer.DirectResult direct) {
		Status status=direct.result().status();
		if(status==Status.OPTIMAL) {
			consider(direct.result().selectedRules());
			if(incumbent==null || incumbent.compareTo(direct.result().cost())!=0)
				throw new IllegalStateException("Direct exact result failed original-model validation");
		}
		return finish(status,status==Status.OPTIMAL ? incumbent : null,direct.kernel());
	}
	private Truth truth(int[] assignment) {
		Truth result=Truth.YES;
		for(int id=0;id<checks.size();id++) {
			Truth value=checks.get(id).evaluate(project(checkScopes[id],assignment));
			if(value==Truth.NO) return Truth.NO;
			if(value==Truth.UNKNOWN) result=Truth.UNKNOWN;
		}
		return result;
	}
	private boolean fixedUnknown(int[] assignment) {
		for(int id=0;id<checks.size();id++) {
			int[] tuple=project(checkScopes[id],assignment);
			if(complete(tuple) && checks.get(id).evaluate(tuple)==Truth.UNKNOWN) return true;
		}
		return false;
	}
	private void consider(int[] assignment) {
		long validationStarted=System.nanoTime();
		try {
		if(!complete(assignment) || truth(assignment)!=Truth.YES) return;
		BigDecimal value=BigDecimal.ZERO;
		for(int owner=0;owner<sizes.length;owner++) value=value.add(nativeCosts==null ? new BigDecimal(nativeInput[owner][assignment[owner]]) : nativeCosts[owner][assignment[owner]]);
		for(int id=0;id<costs.size();id++) value=value.add(evaluateCost(id,project(costScopes[id],assignment)));
		if(incumbent==null || value.compareTo(incumbent)<0 || value.compareTo(incumbent)==0 && lex(assignment,selected)<0) {
			if(incumbent==null) firstUpperNanos=System.nanoTime()-startedNanos;
			if(incumbent==null || value.compareTo(incumbent)<0) bestUpperNanos=System.nanoTime()-startedNanos;
			incumbent=value; selected=assignment.clone();
		}
		} finally { validationNanos+=System.nanoTime()-validationStarted; }
	}
	private BigDecimal evaluateCost(int id,int[] tuple) {
		BigDecimal value=costs.get(id).evaluateExact(tuple);
		if(value.compareTo(costs.get(id).certifiedMinimum())<0)
			throw new IllegalStateException("Cost below certified floor at factor "+id);
		return value;
	}
	private BigDecimal bound(BitSet[] domains) {
		long started=System.nanoTime(); relaxationCalls++;
		try {
			BigDecimal value=BigDecimal.ZERO;
			BigDecimal[] nativeMinima=clusters==null ? null : new BigDecimal[sizes.length];
			BigDecimal[] termMinima=clusters==null ? null : new BigDecimal[costs.size()];
			for(int owner=0;owner<sizes.length;owner++) {
				BigDecimal best=null;
				for(int rule=domains[owner].nextSetBit(0);rule>=0;rule=domains[owner].nextSetBit(rule+1))
					best=min(best,nativeCosts[owner][rule]);
				value=value.add(Objects.requireNonNull(best,"Empty bound domain"));
				if(nativeMinima!=null) nativeMinima[owner]=best;
			}
			for(int id=0;id<costs.size();id++) {
				BigDecimal term=termBound(id,domains); value=value.add(term);
				if(termMinima!=null) termMinima[id]=term;
			}
			return clusters==null ? value : value.max(clusters.bound(domains,nativeMinima,termMinima));
		}
		finally { boundNanos+=System.nanoTime()-started; }
	}
	private BigDecimal termBound(int id,BitSet[] domains) {
		BoundKey key=new BoundKey(id,costScopes[id],domains);
		BigDecimal hit=bounds.get(key);
		if(hit!=null) { boundCacheHits++; return hit; }
		int[][] live=new int[costScopes[id].length][]; long count=1;
		for(int axis=0;axis<live.length;axis++) {
			int owner=costScopes[id][axis];
			if(costClasses[id]==null) live[axis]=domains[owner].stream().toArray();
			else {
				LinkedHashSet<Integer> seen=new LinkedHashSet<>(); List<Integer> reps=new ArrayList<>();
				for(int rule=domains[owner].nextSetBit(0);rule>=0;rule=domains[owner].nextSetBit(rule+1))
					if(seen.add(costClasses[id][axis][rule])) reps.add(rule);
				live[axis]=reps.stream().mapToInt(Integer::intValue).toArray();
			}
			if(count>options.factorCells()/live[axis].length) return costs.get(id).certifiedMinimum();
			count*=live[axis].length;
		}
		BigDecimal best=null; int[] tuple=new int[live.length];
		for(long row=0;row<count;row++) {
			long remaining=row;
			for(int axis=live.length-1;axis>=0;axis--) { tuple[axis]=live[axis][(int)(remaining%live[axis].length)]; remaining/=live[axis].length; }
			best=min(best,evaluateCost(id,tuple));
			if(best.compareTo(costs.get(id).certifiedMinimum())==0) break;
		}
		putBound(key,Objects.requireNonNull(best)); return best;
	}
	private void putBound(BoundKey key,BigDecimal value) {
		long bytes=key.bytes()+valueBytes(value);
		if(bytes>options.cacheBytes()) return;
		while(cacheBytes>options.cacheBytes()-bytes && !bounds.isEmpty()) evictOne();
		while(true) {
			if(resources.admit(residentBytes(),bytes)) { bounds.put(key,value); cacheBytes+=bytes; return; }
			if(bounds.isEmpty()) return;
			evictOne();
		}
	}
	private void evictOne() {
		Iterator<Map.Entry<BoundKey,BigDecimal>> iterator=bounds.entrySet().iterator();
		Map.Entry<BoundKey,BigDecimal> entry=iterator.next(); cacheBytes-=entry.getKey().bytes()+valueBytes(entry.getValue()); iterator.remove();
	}
	private boolean admit(long additional) {
		while(true) {
			if(resources.admit(residentBytes(),additional)) return true;
			if(!bounds.isEmpty()) evictOne();
			else if(clusters!=null && clusters.retainedBytes()>0) clusters.clear();
			else if(!history.isEmpty()) { history.clear(); historyBytes=0; }
			else return false;
		}
	}
	private long residentBytes() { return workingBytes()+pendingBytes+cacheBytes+historyBytes+reservedBytes; }
	private long workingBytes() { return baseBytes+(clusters==null ? 0 : clusters.retainedBytes()); }
	private long estimateBaseBytes() {
		long bytes=1024L+128L*sizes.length+16L*(checks.size()+costs.size());
		for(double[] row:nativeInput) bytes=MemoResourcePolicy.checkedAdd(bytes,24L+8L*row.length);
		if(nativeCosts!=null) for(BigDecimal[] row:nativeCosts) {
			bytes=MemoResourcePolicy.checkedAdd(bytes,48L+16L*row.length);
			for(BigDecimal value:row) bytes=MemoResourcePolicy.checkedAdd(bytes,valueBytes(value));
		}
		for(int[] scope:checkScopes) bytes=MemoResourcePolicy.checkedAdd(bytes,24L+4L*scope.length);
		for(int[] scope:costScopes) bytes=MemoResourcePolicy.checkedAdd(bytes,24L+4L*scope.length);
		if(costClasses!=null) for(int[][] classes:costClasses) if(classes!=null) {
			bytes=MemoResourcePolicy.checkedAdd(bytes,24L+8L*classes.length);
			for(int[] axis:classes) bytes=MemoResourcePolicy.checkedAdd(bytes,24L+4L*axis.length);
		}
		return bytes;
	}
	private static long valueBytes(BigDecimal value) { return 128L+(long)value.precision()/2; }
	private void push(Frame frame) { pending.push(frame); pendingBytes+=frame.bytes; maxPending=Math.max(maxPending,pending.size()); }
	private void pop() { pendingBytes-=pending.pop().bytes; }
	private BigDecimal globalLower() {
		BigDecimal lower=min(incumbent,unknownLower);
		for(Frame frame:pending) lower=min(lower,frame.lower);
		return lower;
	}
	private Status certificate(BigDecimal lower) {
		if(incumbent==null || lower==null) return null;
		if(lower.compareTo(incumbent)>0) throw new IllegalStateException("Lower bound exceeds feasible upper bound");
		if(lower.compareTo(incumbent)==0 && (mode==Mode.LOCAL || exactTieClosed())) return Status.OPTIMAL;
		if(mode==Mode.LOCAL && lower.signum()>0 && incumbent.compareTo(EPSILON_FACTOR.multiply(lower))<=0)
			return Status.EPSILON_OPTIMAL;
		return null;
	}
	private boolean exactTieClosed() {
		if(unknownLower!=null && unknownLower.compareTo(incumbent)<=0) return false;
		for(Frame frame:pending) if(!prunable(frame)) return false;
		return true;
	}
	private boolean prunable(Frame frame) {
		return incumbent!=null && (frame.lower.compareTo(incumbent)>0
			|| frame.lower.compareTo(incumbent)==0 && lex(floor(frame.domains),selected)>=0);
	}
	private void publish(BigDecimal lower) {
		if(Objects.equals(lower,lastLower) && Objects.equals(incumbent,lastUpper)) return;
		if(lower!=null && lastLower!=null && lower.compareTo(lastLower)<0)
			throw new IllegalStateException("Certified global lower bound regressed");
		lastLower=lower; lastUpper=incumbent;
		Progress point=new Progress(nodes,lower,incumbent);
		if(listener!=null) listener.accept(point);
		if(options.trace()) {
			long bytes=96+(lower==null ? 0:valueBytes(lower))+(incumbent==null ? 0:valueBytes(incumbent));
			// Diagnostic history is evictable; production listeners stream without retention.
			if(admit(bytes)) { history.add(point); historyBytes+=bytes; }
		}
	}
	private Result finish(Status status,BigDecimal lower,String reason) {
		publish(lower);
		return new Result(status,lower,incumbent,selected,new Statistics(directExactSolves,directAttempts,
			seedAttempts,seedEvaluations,repairCalls,nodes,relaxationCalls,boundCacheHits,exactCells,pruned,
			maxPending,resources.peakEstimatedBytes(),cacheBytes,kernelNanos,seedNanos,boundNanos,
			searchNanos+(searchStarted==0 ? 0 : System.nanoTime()-searchStarted),setupNanos,validationNanos,
			firstUpperNanos,bestUpperNanos, status==Status.OPTIMAL || status==Status.EPSILON_OPTIMAL ? System.nanoTime()-startedNanos : -1,
			clusters==null ? 0:clusters.statistics().groups(),clusters==null ? 0:clusters.statistics().tableCells(),
			clusters==null ? 0:clusters.statistics().queries(),clusters==null ? 0:clusters.statistics().cacheHits(),
			clusters==null ? 0:clusters.retainedBytes(),clusters==null ? 0:clusters.statistics().buildNanos(),
			clusters==null ? 0:clusters.statistics().queryNanos(),route),
			history,reason,kernelStatistics);
	}
	private BitSet[] initialDomains() {
		BitSet[] result=new BitSet[sizes.length];
		for(int i=0;i<sizes.length;i++) { result[i]=new BitSet(sizes[i]); result[i].set(0,sizes[i]); }
		return result;
	}
	private static int[] partial(BitSet[] domains) {
		int[] result=new int[domains.length];
		for(int i=0;i<result.length;i++) result[i]=domains[i].cardinality()==1 ? domains[i].nextSetBit(0):-1;
		return result;
	}
	private static int[] floor(BitSet[] domains) { return Arrays.stream(domains).mapToInt(d -> d.nextSetBit(0)).toArray(); }
	private int[] preferred(BitSet[] domains) {
		int[] result=new int[sizes.length]; for(int i=0;i<sizes.length;i++) result[i]=preferredRule(i,domains[i]); return result;
	}
	private int preferredRule(int owner,BitSet domain) {
		if(selected!=null && domain.get(selected[owner])) return selected[owner];
		int best=domain.nextSetBit(0);
		for(int rule=domain.nextSetBit(best+1);rule>=0;rule=domain.nextSetBit(rule+1))
			if(nativeCosts[owner][rule].compareTo(nativeCosts[owner][best])<0) best=rule;
		return best;
	}
	private int branch(BitSet[] domains) {
		int best=-1;
		for(int owner=0;owner<domains.length;owner++) if(domains[owner].cardinality()>1
			&& (best<0 || degree[owner]>degree[best] || degree[owner]==degree[best]
				&& domains[owner].cardinality()<domains[best].cardinality())) best=owner;
		return best;
	}
	private static boolean complete(int[] assignment) { for(int rule:assignment) if(rule<0) return false; return true; }
	private static int[] project(int[] scope,int[] assignment) { return Arrays.stream(scope).map(owner -> assignment[owner]).toArray(); }
	private static int lex(int[] left,int[] right) {
		for(int i=0;i<left.length;i++) if(left[i]!=right[i]) return Integer.compare(left[i],right[i]); return 0;
	}
	private static BigDecimal min(BigDecimal a,BigDecimal b) { return a==null ? b : b==null ? a : a.min(b); }
	private static MemoOptimizer.Statistics emptyKernelStatistics() {
		return new MemoOptimizer.Statistics(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
	}
	private static final class Frame {
		private BitSet[] domains;
		private BigDecimal lower;
		private boolean kernelTried;
		private int branch=-1;
		private final long bytes;
		private Frame(BitSet[] domains,BigDecimal lower,boolean kernelTried) {
			this.domains=domains; this.lower=lower; this.kernelTried=kernelTried;
			bytes=estimateBytes(domains);
		}
		private static long estimateBytes(BitSet[] domains) {
			long size=128L+8L*domains.length;
			for(BitSet domain:domains) size+=48L+8L*((domain.length()+63L)/64L);
			return size;
		}
	}
	private static final class BoundKey {
		private final int factor;
		private final long[][] domains;
		private final int hash;
		private BoundKey(int factor,int[] scope,BitSet[] values) {
			this.factor=factor; domains=new long[scope.length][];
			for(int i=0;i<scope.length;i++) domains[i]=values[scope[i]].toLongArray();
			hash=31*factor+Arrays.deepHashCode(domains);
		}
		private long bytes() { long size=96L+24L*domains.length; for(long[] d:domains) size+=8L*d.length; return size; }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object obj) { return obj instanceof BoundKey other && factor==other.factor && Arrays.deepEquals(domains,other.domains); }
	}
}

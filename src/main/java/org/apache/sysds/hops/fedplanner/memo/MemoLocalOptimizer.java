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
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Options;
import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Progress;
import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;

/** Certificate-first Local policy. The whole-model exact optimizer is never
 * called. A feasible incumbent and a proof cover have separate lifetimes and
 * choices. Incremental boundary messages strengthen a relaxation; original
 * domains remain available for complete, UNKNOWN-safe conditional search. */
final class MemoLocalOptimizer {
	private static final BigDecimal EPSILON=new BigDecimal("1.05");
	private final int[] sizes;
	private final boolean[] coverage;
	private final double[][] nativeCosts;
	private final List<Check> checks;
	private final List<CostTerm> costs;
	private final int[][] checkScopes,costScopes;
	private final int[] degrees;
	private final Options options;
	private final MemoResourcePolicy resources;
	private final Consumer<Progress> listener;
	private final PriorityQueue<Region> open=new PriorityQueue<>(Comparator
		.comparing((Region r)->r.lower).thenComparingLong(r->r.id));
	private final List<Progress> history=new ArrayList<>();
	private final LinkedHashMap<BoundKey,BigDecimal> cache=new LinkedHashMap<>(16,.75f,true);
	private final long started=System.nanoTime();
	private final long baseBytes,domainBytes;
	private long messageSetupEstimate=-1;
	private MemoLocalMessages messages;
	private BitSet[] root;
	private BigDecimal rootLower,upper,unknownLower,deferredLower,lastLower,lastUpper;
	private int[] selected,lastCandidate;
	private boolean rootActive=true;
	private long sequence,nodes,pruned,maxPending,relaxations,boundHits,exactCells,cacheBytes,openBytes,historyBytes,reservedBytes;
	private long setupNanos,seedNanos,boundNanos,searchNanos,searchStartedNanos,validationNanos,messageNanos;
	private long firstUpper=-1,bestUpper=-1,seedAttempts,seedEvaluations,repairs,unknownRegions,deferredRegions;
	private long propagationNanos,propagatedRules,propagationChecks;
	private String messageDecline="NONE";

	MemoLocalOptimizer(int[] sizes,boolean[] coverage,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> costs,Options options,Consumer<Progress> listener) {
		// Inputs are the validated immutable snapshots owned by the public facade.
		this.sizes=sizes; this.coverage=coverage; this.nativeCosts=nativeCosts;
		this.checks=checks; this.costs=costs; this.options=options; this.listener=listener;
		resources=new MemoResourcePolicy(options.maxResidentBytes());
		MemoLocalMessages.validateClasses(sizes,checks,costs);
		checkScopes=checks.stream().map(Check::owners).toArray(int[][]::new);
		costScopes=costs.stream().map(CostTerm::owners).toArray(int[][]::new);
		degrees=new int[sizes.length];
		long bytes=2048L+256L*sizes.length+96L*(checks.size()+costs.size());
		for(int size:sizes) bytes=MemoResourcePolicy.checkedAdd(bytes,32L+8L*size);
		for(int[] scope:checkScopes) { bytes=MemoResourcePolicy.checkedAdd(bytes,8L*scope.length); for(int v:scope) degrees[v]++; }
		for(int[] scope:costScopes) { bytes=MemoResourcePolicy.checkedAdd(bytes,8L*scope.length); for(int v:scope) degrees[v]++; }
		baseBytes=bytes; domainBytes=estimateDomainBytes();
	}

	Result optimize() {
		for(boolean complete:coverage) if(!complete) return finish(Status.UNRESOLVED,"Incomplete candidate coverage");
		for(int size:sizes) if(size==0) return finish(Status.INFEASIBLE,"Empty original domain");
		long before=System.nanoTime();
		if(!resources.admit(0,MemoResourcePolicy.checkedAdd(baseBytes,domainBytes))) return finish(Status.RESOURCE_EXHAUSTED,"Cannot retain Local proof domains");
		root=new BitSet[sizes.length];
		for(int v=0;v<sizes.length;v++) { root[v]=new BitSet(sizes[v]); root[v].set(0,sizes[v]); }
		rootLower=coarseBound(root,false); setupNanos+=System.nanoTime()-before;
		publish(); action("INITIAL_BOUND",-1,0);
		if(options.seedWorkLimit()>0) {
			before=System.nanoTime(); seedAttempts++;
			MemoFeasibleSeed.Result seed=MemoFeasibleSeed.findFirst(sizes,nativeCosts,checks,costs,root,
				options.seedWorkLimit(),resources.withResidentBytes(residentBytes()));
			seedNanos+=System.nanoTime()-before; seedEvaluations+=seed.evaluations(); repairs+=seed.repairs();
			consider(seed.selectedRules()); action("FIRST_FEASIBLE",-1,seed.evaluations());
			Status certificate=certificate(); if(certificate!=null) return finish(certificate,"Initial feasible certificate");
		}
		// First witness acquisition is allowed to yield without a plan. The proof
		// cover will continue feasibility search rather than claim infeasibility.
		if(upper!=null && Boolean.parseBoolean(System.getProperty("sysds.fedplanner.memo.local.improveSeed",Boolean.toString(options.improveSeed())))) {
			List<int[]> scopes=seedScopes();
			long remaining=Math.max(0,options.seedWorkLimit()-seedEvaluations);
			for(int i=0;i<scopes.size() && i<4 && remaining>0;i++) {
				before=System.nanoTime(); seedAttempts++;
				MemoFeasibleSeed.Result improved=MemoFeasibleSeed.improveFirst(sizes,nativeCosts,checks,costs,root,selected,
					List.of(scopes.get(i)),Math.min(remaining,4096),resources.withResidentBytes(residentBytes()));
				seedNanos+=System.nanoTime()-before; seedEvaluations+=improved.evaluations(); repairs+=improved.repairs();
				remaining=Math.max(0,remaining-improved.evaluations());
				consider(improved.selectedRules()); action("SEED_IMPROVEMENT",-1,improved.evaluations());
				Status certificate=certificate(); if(certificate!=null) return finish(certificate,"Improved seed certificate");
			}
		}
		// Optional feasibility refinement, after the first certificate opportunity.
		// This API cannot invoke any cost optimizer, tree solve or elimination.
		MemoOptimizer.Limits propagationLimits=MemoOptimizer.Limits.exactDefaults();
		if(admit(MemoOptimizer.estimatePropagationBytes(sizes,checks,propagationLimits))) {
			before=System.nanoTime();
			MemoOptimizer.PropagationResult propagation=new MemoOptimizer(sizes,coverage,nativeCosts,checks,
				List.of(),propagationLimits).propagateOnly(root);
			propagationNanos+=System.nanoTime()-before;
			propagatedRules=propagation.statistics().removedRules();
			propagationChecks=propagation.statistics().checkEvaluations();
			if(!propagation.feasible()) {
				rootActive=false; return finish(Status.INFEASIBLE,"Original legality propagation proves infeasibility");
			}
			root=propagation.domains(); rootLower=rootLower.max(coarseBound(root,false)); publish();
			action("LEGALITY_PROPAGATION",-1,propagationChecks);
			Status certificate=certificate(); if(certificate!=null) return finish(certificate,"Propagated-domain certificate");
		}
		boolean useMessages=options.clusterCells()>0 && Boolean.parseBoolean(
			System.getProperty("sysds.fedplanner.memo.local.messages","true"));
		long messageSetup=messageSetupBytes();
		if(useMessages && admit(messageSetup)) {
			before=System.nanoTime();
			messages=new MemoLocalMessages(sizes,nativeCosts,checks,costs,root,
				resources.withResidentBytes(baseBytes+domainBytes+messageSetup));
			setupNanos+=System.nanoTime()-before;
			long cellLimit=Math.max(1,options.clusterCells());
			while(!messages.complete()) {
				before=System.nanoTime();
				MemoLocalMessages.Step step=messages.refine(cellLimit,
					resources.withResidentBytes(baseBytes+domainBytes+messageSetup+cacheBytes+historyBytes));
				messageNanos+=System.nanoTime()-before;
				if(!step.changed()) {
					messageDecline=step.reason(); action("MESSAGE_DECLINED",step.owner(),step.cells()); break;
				}
				BigDecimal lower=messages.initialBound(); relaxations++;
				if(lower==null) { rootActive=false; return finish(Status.INFEASIBLE,"Relaxed messages prove original infeasibility"); }
				rootLower=rootLower.max(lower); publish();
				// A lower-bound update may already certify the old witness.
				Status certificate=certificate();
				if(certificate!=null) { action("MESSAGE",step.owner(),step.cells()); return finish(certificate,"Incremental message certificate"); }
				consider(messages.reconstruct(selected==null ? preferred(root):selected));
				action("MESSAGE",step.owner(),step.cells());
				certificate=certificate(); if(certificate!=null) return finish(certificate,"Message witness certificate");
			}
		}
		if(useMessages && messages==null) messageDecline="SETUP_MEMORY_REJECTED";
		searchStartedNanos=System.nanoTime();
		try { return search(); }
		finally { searchNanos+=System.nanoTime()-searchStartedNanos; searchStartedNanos=0; }
	}

	private Result search() {
		Region initial=new Region(root,rootLower,sequence++);
		if(!admit(initial.bytes)) return finish(Status.RESOURCE_EXHAUSTED,"Cannot retain initial frontier");
		open.add(initial); openBytes+=initial.bytes; maxPending=1; rootActive=false;
		while(true) {
			publish(); Status certificate=certificate();
			if(certificate!=null) return finish(certificate,"Requested global certificate established");
			if(open.isEmpty()) return finish(unknownLower!=null ? Status.UNRESOLVED : upper==null ? Status.INFEASIBLE:Status.OPTIMAL,
				unknownLower!=null ? "UNKNOWN proof obligations prevent certification":"Original cover exhausted");
			Region region=open.peek();
			if(upper!=null && region.lower.compareTo(upper)>=0) { remove(); pruned++; continue; }
			if(upper!=null && region.lower.signum()>0 && EPSILON.multiply(region.lower).compareTo(upper)>=0) {
				deferredLower=min(deferredLower,region.lower); deferredRegions++; remove(); pruned++; continue;
			}
			if(nodes>=options.diagnosticNodes()) return finish(Status.RESOURCE_EXHAUSTED,"Explicit diagnostic node cap");
			nodes++;
			int[] partial=partial(region.domains); Truth truth=truth(partial);
			if(truth==Truth.NO) { remove(); pruned++; continue; }
			if(fixedUnknown(partial)) { unknownLower=min(unknownLower,region.lower); unknownRegions++; remove(); continue; }
			if(complete(partial)) {
				if(truth==Truth.YES) consider(partial); else { unknownLower=min(unknownLower,region.lower); unknownRegions++; }
				remove(); continue;
			}
			// Cheap validation may improve UB, but it never chooses proof branch values.
			consider(preferred(region.domains));
			Status afterWitness=certificate(); if(afterWitness!=null) return finish(afterWitness,"Search witness certificate");
			int owner=branch(region.domains),rule=region.domains[owner].nextSetBit(0);
			long childBytes=2*region.bytes+256;
			if(!admit(childBytes)) return finish(Status.RESOURCE_EXHAUSTED,"Cannot retain both children of proof cover");
			reservedBytes+=childBytes;
			BitSet[] left=region.domains.clone(),right=region.domains.clone();
			left[owner]=new BitSet(sizes[owner]); left[owner].set(rule);
			right[owner]=(BitSet)region.domains[owner].clone(); right[owner].clear(rule);
			BigDecimal leftLower=restrictedBound(left,region.lower),rightLower=restrictedBound(right,region.lower);
			// Publication is atomic with respect to the old cover: both children
			// have been allocated and bounded before removing their parent.
			Region a=leftLower==null ? null:new Region(left,leftLower,sequence++);
			Region b=rightLower==null ? null:new Region(right,rightLower,sequence++);
			reservedBytes-=childBytes;
			remove(); if(a!=null) add(a); if(b!=null) add(b);
			action("BOUNDARY_SPLIT",owner,2);
		}
	}

	private BigDecimal restrictedBound(BitSet[] domains,BigDecimal inherited) {
		BigDecimal value=coarseBound(domains,true).max(inherited);
		if(messages!=null) {
			BigDecimal relaxed=messages.lowerBound(domains);
			if(relaxed==null) return null;
			value=value.max(relaxed);
		}
		return value;
	}
	private BigDecimal coarseBound(BitSet[] domains,boolean refine) {
		long before=System.nanoTime(); relaxations++;
		try {
			BigDecimal value=BigDecimal.ZERO;
			for(int v=0;v<sizes.length;v++) {
				double best=Double.POSITIVE_INFINITY;
				for(int r=domains[v].nextSetBit(0);r>=0;r=domains[v].nextSetBit(r+1)) best=Math.min(best,nativeCosts[v][r]);
				value=value.add(new BigDecimal(best));
			}
			for(int id=0;id<costs.size();id++) value=value.add(costScopes[id].length==0 ? evaluate(id,new int[0])
				: refine ? factorBound(id,domains):costs.get(id).certifiedMinimum());
			return value;
		} finally { boundNanos+=System.nanoTime()-before; }
	}
	private BigDecimal factorBound(int id,BitSet[] domains) {
		int[] scope=costScopes[id]; BoundKey key=new BoundKey(id,scope,domains);
		BigDecimal hit=cache.get(key); if(hit!=null) { boundHits++; return hit; }
		long cells=1;
		for(int owner:scope) {
			if(cells>options.factorCells()/domains[owner].cardinality()) return costs.get(id).certifiedMinimum();
			cells*=domains[owner].cardinality();
		}
		int[][] live=new int[scope.length][];
		for(int axis=0;axis<scope.length;axis++) live[axis]=domains[scope[axis]].stream().toArray();
		BigDecimal best=null; int[] tuple=new int[scope.length];
		for(long row=0;row<cells;row++) {
			long rest=row;
			for(int axis=scope.length-1;axis>=0;axis--) { tuple[axis]=live[axis][(int)(rest%live[axis].length)]; rest/=live[axis].length; }
			best=min(best,evaluate(id,tuple)); exactCells++;
			if(best.compareTo(costs.get(id).certifiedMinimum())==0) break;
		}
		long bytes=256L+key.bytes();
		// Cache allocation never consumes memory reserved for frontier publication.
		if(cacheBytes+bytes<=options.cacheBytes() && cacheBytes+bytes<=4L*1024*1024 && resources.admit(residentBytes(),bytes)) {
			cache.put(key,best); cacheBytes+=bytes;
		}
		return best;
	}

	private List<int[]> seedScopes() {
		List<Integer> ids=new ArrayList<>();
		for(int id=0;id<costScopes.length;id++) if(costScopes[id].length>0) ids.add(id);
		ids.sort(Comparator.comparingInt((Integer id)->costScopes[id].length).thenComparingInt(Integer::intValue));
		List<int[]> result=new ArrayList<>(); BitSet touched=new BitSet();
		for(int id:ids) {
			int[] scope=costScopes[id]; long cells=1; boolean fresh=false;
			for(int v:scope) { cells=cells>256L/sizes[v] ? 257:cells*sizes[v]; fresh|=!touched.get(v) && sizes[v]>1; }
			if(!fresh || cells>256) continue;
			result.add(scope); for(int v:scope) touched.set(v);
			if(result.size()==4) break;
		}
		return result;
	}
	private int[] preferred(BitSet[] domains) {
		int[] result=new int[sizes.length];
		for(int v=0;v<sizes.length;v++) {
			int best=domains[v].nextSetBit(0);
			for(int r=domains[v].nextSetBit(best+1);r>=0;r=domains[v].nextSetBit(r+1))
				if(nativeCosts[v][r]<nativeCosts[v][best]) best=r;
			result[v]=best;
		}
		return result;
	}
	private int branch(BitSet[] domains) {
		int best=-1;
		for(int v=0;v<sizes.length;v++) if(domains[v].cardinality()>1 &&
			(best<0 || degrees[v]>degrees[best] || degrees[v]==degrees[best] && domains[v].cardinality()<domains[best].cardinality())) best=v;
		return best;
	}
	private void consider(int[] candidate) {
		if(candidate==null || !complete(candidate) || Arrays.equals(candidate,selected) || Arrays.equals(candidate,lastCandidate)) return;
		long before=System.nanoTime();
		try {
			lastCandidate=candidate.clone();
			if(truth(candidate)!=Truth.YES) return;
			BigDecimal cost=BigDecimal.ZERO;
			for(int v=0;v<sizes.length;v++) cost=cost.add(new BigDecimal(nativeCosts[v][candidate[v]]));
			for(int id=0;id<costs.size();id++) cost=cost.add(evaluate(id,project(costScopes[id],candidate)));
			if(upper==null || cost.compareTo(upper)<0) {
				if(upper==null) firstUpper=System.nanoTime()-started;
				bestUpper=System.nanoTime()-started; upper=cost; selected=candidate.clone(); publish();
			}
		} finally { validationNanos+=System.nanoTime()-before; }
	}
	private Truth truth(int[] assignment) {
		Truth result=Truth.YES;
		for(int id=0;id<checks.size();id++) {
			Truth t=checks.get(id).evaluate(project(checkScopes[id],assignment));
			if(t==Truth.NO) return Truth.NO; if(t==Truth.UNKNOWN) result=t;
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
	private BigDecimal evaluate(int id,int[] tuple) {
		BigDecimal value=costs.get(id).evaluateExact(tuple);
		if(value.compareTo(costs.get(id).certifiedMinimum())<0) throw new IllegalStateException("Cost below certified floor");
		return value;
	}
	private BigDecimal globalLower() {
		if(rootLower!=null && upper!=null && rootLower.compareTo(upper)>0)
			throw new IllegalStateException("Root relaxation exceeds original feasible cost");
		BigDecimal lower=min(upper,min(unknownLower,deferredLower));
		if(rootActive) return min(lower,rootLower);
		if(!open.isEmpty()) lower=min(lower,open.peek().lower);
		return lower;
	}
	private Status certificate() {
		BigDecimal lower=globalLower();
		if(lower==null || upper==null) return null;
		if(lower.compareTo(upper)>0) throw new IllegalStateException("Invalid Local bounds");
		if(lower.compareTo(upper)==0) return Status.OPTIMAL;
		return lower.signum()>0 && upper.compareTo(EPSILON.multiply(lower))<=0 ? Status.EPSILON_OPTIMAL:null;
	}
	private void publish() {
		BigDecimal lower=globalLower();
		if(lower==null || lower.equals(lastLower) && java.util.Objects.equals(upper,lastUpper)) return;
		if(lastLower!=null && lower.compareTo(lastLower)<0) throw new IllegalStateException("Local lower bound regressed");
		lastLower=lower; lastUpper=upper;
		Progress point=new Progress(nodes,lower,upper);
		if(listener!=null) listener.accept(point);
		if(options.trace() && historyBytes<1024L*1024 && resources.admit(residentBytes(),1024)) {
			history.add(point); historyBytes+=1024;
		}
	}
	private void action(String kind,int owner,long work) {
		if(Boolean.getBoolean("sysds.fedplanner.memo.trace") || Boolean.getBoolean("sysds.fedplanner.memo.dual.diagnostics"))
			System.err.println("[MemoLocalAction] {\"kind\":\""+kind+"\",\"owner\":"+owner+",\"work\":"+work
				+",\"completedMessages\":"+(messages==null ? 0:messages.statistics().completedBuckets())
				+",\"variables\":"+sizes.length+",\"lowerBound\":\""+globalLower()+"\",\"upperBound\":\""+upper+"\"}");
	}
	private Result finish(Status status,String reason) {
		publish(); MemoLocalMessages.Statistics s=messages==null ? null:messages.statistics();
		long elapsed=System.nanoTime()-started;
		MemoDualModeOptimizer.LocalStatistics localStats=new MemoDualModeOptimizer.LocalStatistics(
			sizes.length,sizes.length+checks.size()+costs.size(),s==null ? 0:s.completedBuckets(),
			sizes.length-(s==null ? 0:s.completedBuckets()),s==null ? 0:s.touchedVariables(),
			s==null ? 0:s.touchedOriginalFactors(),s==null ? 0:s.candidatePreparations(),
			s==null ? 0:s.memoryRejections(),resources.stats().rejections(),open.size()+(rootActive ? 1:0),
			unknownRegions,deferredRegions,messageDecline,propagationNanos,propagatedRules,propagationChecks);
		if(Boolean.getBoolean("sysds.fedplanner.memo.trace") || Boolean.getBoolean("sysds.fedplanner.memo.dual.diagnostics"))
			System.err.println("[MemoLocalSummary] {\"variables\":"+localStats.variables()
				+",\"originalFactors\":"+localStats.originalFactors()+",\"completedMessages\":"+localStats.completedMessages()
				+",\"untouchedBuckets\":"+localStats.untouchedBuckets()+",\"messageTouchedVariables\":"+localStats.messageTouchedVariables()
				+",\"messageTouchedFactors\":"+localStats.messageTouchedFactors()+",\"candidatePreparations\":"+localStats.candidatePreparations()
				+",\"messageMemoryRejections\":"+localStats.messageMemoryRejections()+",\"memoryRejections\":"+localStats.memoryRejections()
				+",\"activeRegions\":"+localStats.activeRegions()+",\"unknownRegions\":"+unknownRegions
				+",\"deferredRegions\":"+deferredRegions+",\"messageDecline\":\""+messageDecline+"\""
				+",\"propagationNanos\":"+propagationNanos+",\"propagatedRules\":"+propagatedRules
				+",\"propagationChecks\":"+propagationChecks+"}");
		return new Result(status,globalLower(),upper,selected,new MemoDualModeOptimizer.Statistics(
			0,0,seedAttempts,seedEvaluations,repairs,nodes,relaxations,boundHits,exactCells+(s==null ? 0:s.evaluatedCells()),pruned,
			maxPending,resources.peakEstimatedBytes(),cacheBytes,messageNanos,seedNanos,boundNanos,
			searchNanos+(searchStartedNanos==0 ? 0:System.nanoTime()-searchStartedNanos),setupNanos,validationNanos,
			firstUpper,bestUpper,status==Status.OPTIMAL || status==Status.EPSILON_OPTIMAL ? elapsed:-1,
			s==null ? 0:s.completedBuckets(),s==null ? 0:s.evaluatedCells(),s==null ? 0:s.boundQueries(),0,
			messages==null ? 0:messages.retainedBytes(),messageNanos,0,"LOCAL_CERTIFICATE_FIRST"),history,reason,
			new MemoOptimizer.Statistics(nodes,0,0,0,0,0,0,0,0,0,s==null ? 0:s.costEvaluations(),0,pruned,0,0,0,0,0,0,0,0,0,0,0,
				s==null ? 0:s.completedBuckets(),s==null ? 0:s.evaluatedCells(),0,0),localStats);
	}
	private boolean admit(long additional) {
		if(resources.admit(residentBytes(),additional)) return true;
		cache.clear(); cacheBytes=0; history.clear(); historyBytes=0;
		return resources.admit(residentBytes(),additional);
	}
	private long residentBytes() { return baseBytes+domainBytes+openBytes+cacheBytes+historyBytes+reservedBytes+
		(messages==null ? 0:messageSetupBytes()+messages.retainedBytes()); }
	private long estimateDomainBytes() { long n=64+32L*sizes.length; for(int size:sizes) n+=48+8L*((size+63L)/64L); return n; }
	private long messageSetupBytes() {
		if(messageSetupEstimate>=0) return messageSetupEstimate;
		long n=2048+512L*sizes.length+256L*(checks.size()+costs.size());
		for(int[] scope:costScopes) for(int v:scope) n=MemoResourcePolicy.checkedAdd(n,128L+16L*sizes[v]);
		for(int[] scope:checkScopes) for(int v:scope) n=MemoResourcePolicy.checkedAdd(n,128L+16L*sizes[v]);
		return messageSetupEstimate=n;
	}
	private void add(Region region) { open.add(region); openBytes+=region.bytes; maxPending=Math.max(maxPending,open.size()); }
	private void remove() { openBytes-=open.remove().bytes; }
	private static BigDecimal min(BigDecimal a,BigDecimal b) { return a==null ? b:b==null ? a:a.min(b); }
	private static boolean complete(int[] values) { for(int value:values) if(value<0) return false; return true; }
	private static int[] project(int[] scope,int[] values) { return Arrays.stream(scope).map(v->values[v]).toArray(); }
	private static int[] partial(BitSet[] domains) {
		int[] result=new int[domains.length]; for(int v=0;v<result.length;v++) result[v]=domains[v].cardinality()==1 ? domains[v].nextSetBit(0):-1; return result;
	}
	private record Region(BitSet[] domains,BigDecimal lower,long id,long bytes) {
		Region(BitSet[] domains,BigDecimal lower,long id) { this(domains,lower,id,128L+64L*domains.length+
			Arrays.stream(domains).mapToLong(d->8L*((d.length()+63L)/64L)).sum()); }
	}
	private static final class BoundKey {
		private final int id; private final long[][] domains;
		BoundKey(int id,int[] scope,BitSet[] all) { this.id=id; domains=new long[scope.length][]; for(int i=0;i<scope.length;i++) domains[i]=all[scope[i]].toLongArray(); }
		long bytes() { return 64L+Arrays.stream(domains).mapToLong(d->32L+8L*d.length).sum(); }
		@Override public int hashCode() { return 31*id+Arrays.deepHashCode(domains); }
		@Override public boolean equals(Object other) { return other instanceof BoundKey key && id==key.id && Arrays.deepEquals(domains,key.domains); }
	}
}

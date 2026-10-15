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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Builds an initial feasible upper-bound candidate without changing the exact
 * solver's proof domains. The bounded work in this class is only a seed
 * heuristic: stopping it never removes a rule or proves the original problem
 * infeasible.
 */
public final class MemoFeasibleSeed {
	public record Result(int[] selectedRules, BigDecimal cost, long evaluations, int repairs,
		MemoOptimizer.Result fullProof, String reason) {
		public Result {
			selectedRules = selectedRules == null ? null : selectedRules.clone();
			reason = Objects.requireNonNull(reason);
		}
		@Override public int[] selectedRules() {
			return selectedRules == null ? null : selectedRules.clone();
		}
	}

	private enum SearchStatus { COMPLETE, LIMITED }
	private static final class SearchResult {
		private SearchStatus status;
		private int[] selected;
		private BigDecimal cost;
		private boolean unknown;
		private MemoOptimizer.Result proof;
	}

	private final int[] sizes;
	private final double[][] nativeCosts;
	private final List<MemoOptimizer.Check> checks;
	private final List<MemoOptimizer.CostTerm> costs;
	private final BitSet[] proofDomains;
	private final MemoResourcePolicy resources;
	private final long workLimit;
	private long evaluations;
	private int repairs;

	private MemoFeasibleSeed(int[] sizes, double[][] nativeCosts, List<MemoOptimizer.Check> checks,
		List<MemoOptimizer.CostTerm> costs, BitSet[] proofDomains, long workLimit,
		MemoResourcePolicy resources) {
		this.sizes = sizes.clone();
		this.nativeCosts = copyAndValidateNative(nativeCosts, sizes);
		this.checks = List.copyOf(checks);
		this.costs = List.copyOf(costs);
		this.proofDomains = copyAndValidateDomains(proofDomains, sizes);
		this.resources = resources;
		if(workLimit < 0)
			throw new IllegalArgumentException("Negative seed work limit");
		this.workLimit = workLimit;
		validateScopes();
	}

	public static Result find(int[] sizes, double[][] nativeCosts, List<MemoOptimizer.Check> checks,
		List<MemoOptimizer.CostTerm> costs, BitSet[] proofDomains, long workLimit,
		MemoResourcePolicy resources) {
		Objects.requireNonNull(sizes);
		Objects.requireNonNull(nativeCosts);
		Objects.requireNonNull(checks);
		Objects.requireNonNull(costs);
		Objects.requireNonNull(resources);
		if(workLimit<0) throw new IllegalArgumentException("Negative seed work limit");
		MemoResourcePolicy seedResources=resources;
		if(workLimit>0) {
			long workspace=estimatedWorkspace(sizes,nativeCosts,proofDomains);
			if(!resources.admit(workspace)) return new Result(null,null,0,0,null,"RESOURCE_REJECTED");
			seedResources=resources.withResidentBytes(workspace);
		}
		return new MemoFeasibleSeed(sizes,nativeCosts,checks,costs,proofDomains,workLimit,seedResources).find();
	}

	/**
	 * Improves an already feasible incumbent by exactly solving bounded conditional scopes.  Variables
	 * outside each scope are fixed to the incumbent, and neither skipped nor infeasible conditional
	 * blocks make any statement about feasibility of the original proof domains.
	 */
	public static Result improve(int[] sizes, double[][] nativeCosts, List<MemoOptimizer.Check> checks,
		List<MemoOptimizer.CostTerm> costs, BitSet[] proofDomains, int[] initialSelected,
		List<int[]> scopes, long workLimit, MemoResourcePolicy resources) {
		Objects.requireNonNull(sizes);
		Objects.requireNonNull(nativeCosts);
		Objects.requireNonNull(checks);
		Objects.requireNonNull(costs);
		Objects.requireNonNull(initialSelected);
		Objects.requireNonNull(scopes);
		Objects.requireNonNull(resources);
		if(workLimit<0) throw new IllegalArgumentException("Negative seed work limit");
		if(initialSelected.length!=sizes.length)
			throw new IllegalArgumentException("Initial selection owner mismatch");
		for(int[] scope:scopes) Objects.requireNonNull(scope,"Null improvement scope");
		long workspace=estimatedImproveWorkspace(sizes,nativeCosts,proofDomains,initialSelected,scopes);
		if(!resources.admit(workspace)) return new Result(null,null,0,0,null,"RESOURCE_REJECTED");
		MemoFeasibleSeed seed=new MemoFeasibleSeed(sizes,nativeCosts,checks,costs,proofDomains,workLimit,
			resources.withResidentBytes(workspace));
		return seed.improve(initialSelected,scopes);
	}

	private Result find() {
		for(BitSet domain : proofDomains)
			if(domain.isEmpty())
				return result(null,null,null,"EMPTY_PROOF_DOMAIN");
		if(workLimit == 0)
			return result(null,null,null,"WORK_LIMIT");

		int[] greedy = new int[sizes.length];
		Arrays.fill(greedy,-1);
		BitSet blockedChecks = new BitSet(checks.size());
		int deadOwner = greedy(greedy,blockedChecks);
		if(deadOwner < 0 && fullyFeasible(greedy)) {
			BigDecimal greedyCost = fullCost(greedy);
			return result(greedy,greedyCost,null,"GREEDY_FEASIBLE");
		}

		if(evaluations >= workLimit)
			return result(null,null,null,"WORK_LIMIT");
		BitSet region = repairRegion(greedy,deadOwner,blockedChecks);
		while(true) {
			BitSet[] restrictions = conditionalDomains(greedy,region);
			repairs++;
			SearchResult repair = search(restrictions);
			if(repair.selected != null && fullyFeasible(repair.selected)) {
				BigDecimal repairedCost = fullCost(repair.selected);
				if(sameDomains(restrictions,proofDomains) && repair.status == SearchStatus.COMPLETE && !repair.unknown)
					return result(repair.selected,repairedCost,exactResult(repair),"FULL_PROOF_AFTER_REPAIR");
				return result(repair.selected,repairedCost,null,
					repair.status == SearchStatus.COMPLETE ? "REPAIRED" : "REPAIRED_AT_WORK_LIMIT");
			}
			if(repair.status == SearchStatus.LIMITED)
				return result(null,null,null,"WORK_LIMIT");
			BitSet expanded = expandRegion(region);
			if(expanded.equals(region)) {
				if(region.cardinality() == sizes.length)
					return result(null,null,repair.unknown ? null : exactResult(repair),
						repair.unknown ? "UNRESOLVED_UNKNOWN" : "INFEASIBLE_PROOF");
				expanded.set(0,sizes.length);
			}
			region = expanded;
		}
	}

	private Result improve(int[] initialSelected, List<int[]> sourceScopes) {
		int[] incumbent=initialSelected.clone();
		for(int owner=0;owner<sizes.length;owner++)
			if(incumbent[owner]<0 || incumbent[owner]>=sizes[owner] || !proofDomains[owner].get(incumbent[owner]))
				throw new IllegalArgumentException("Initial selection is outside proof domain for owner "+owner);
		if(!fullyFeasible(incumbent))
			throw new IllegalArgumentException("Initial selection is not fully legal in the original model");
		BigDecimal incumbentCost=fullCost(incumbent);
		List<BitSet> scopes=copyAndValidateScopes(sourceScopes);
		if(workLimit==0 || scopes.isEmpty())
			return result(incumbent,incumbentCost,null,workLimit==0 ? "WORK_LIMIT" : "NO_SCOPES");

		boolean improvedAny=false;
		while(evaluations<workLimit) {
			boolean improvedSweep=false;
			for(BitSet scope:scopes) {
				if(scope.isEmpty() || evaluations>=workLimit) continue;
				BitSet[] restrictions=conditionalDomains(incumbent,scope);
				long remaining=workLimit-evaluations;
				if(kernelWorkEstimate(restrictions,remaining)<0) continue;
				repairs++;
				SearchResult candidate=search(restrictions);
				if(candidate.status!=SearchStatus.COMPLETE || candidate.unknown || candidate.selected==null
					|| !fullyFeasible(candidate.selected)) continue;
				BigDecimal candidateCost=fullCost(candidate.selected);
				if(candidateCost.compareTo(incumbentCost)<0) {
					incumbent=candidate.selected.clone(); incumbentCost=candidateCost;
					improvedSweep=true; improvedAny=true;
				}
			}
			if(!improvedSweep) break;
		}
		String reason=evaluations>=workLimit ? improvedAny ? "IMPROVED_AT_WORK_LIMIT" : "WORK_LIMIT"
			: improvedAny ? "IMPROVED" : "NO_IMPROVEMENT";
		return result(incumbent,incumbentCost,null,reason);
	}

	private List<BitSet> copyAndValidateScopes(List<int[]> scopes) {
		java.util.ArrayList<BitSet> result=new java.util.ArrayList<>(scopes.size());
		for(int[] source:scopes) {
			BitSet scope=new BitSet(sizes.length);
			for(int owner:source) {
				if(owner<0 || owner>=sizes.length) throw new IllegalArgumentException("Unknown owner "+owner);
				scope.set(owner);
			}
			result.add(scope);
		}
		return List.copyOf(result);
	}

	/** Returns -1 for a complete assignment, otherwise the owner where greedy stopped. */
	private int greedy(int[] selected, BitSet blockedChecks) {
		for(int owner=0; owner<sizes.length; owner++) {
			int best=-1;
			BigDecimal bestScore=null;
			for(int rule=proofDomains[owner].nextSetBit(0); rule>=0;
				rule=proofDomains[owner].nextSetBit(rule+1)) {
				if(!consume()) return owner;
				selected[owner]=rule;
				boolean legal=true;
				for(int id=0; id<checks.size(); id++) {
					MemoOptimizer.Check check=checks.get(id);
					if(!contains(check.owners(),owner) || !assigned(check.owners(),selected)) continue;
					if(check.evaluate(project(check.owners(),selected)) != MemoOptimizer.Truth.YES) {
						legal=false; blockedChecks.set(id);
					}
				}
				if(legal) {
					BigDecimal score=exact(nativeCosts[owner][rule]);
					for(MemoOptimizer.CostTerm cost : costs)
						if(contains(cost.owners(),owner) && assigned(cost.owners(),selected))
							score=score.add(cost.evaluateExact(project(cost.owners(),selected)));
					if(bestScore==null || score.compareTo(bestScore)<0 || score.compareTo(bestScore)==0 && rule<best) {
						best=rule; bestScore=score;
					}
				}
				selected[owner]=-1;
			}
			if(best<0) return owner;
			selected[owner]=best;
		}
		return -1;
	}

	private BitSet repairRegion(int[] selected, int deadOwner, BitSet blockedChecks) {
		BitSet region=new BitSet(sizes.length);
		for(int owner=0; owner<sizes.length; owner++) if(selected[owner]<0) region.set(owner);
		if(deadOwner>=0) region.set(deadOwner);
		for(int id=blockedChecks.nextSetBit(0); id>=0; id=blockedChecks.nextSetBit(id+1))
			for(int owner:checks.get(id).owners()) region.set(owner);
		return expandRegion(region);
	}

	private BitSet expandRegion(BitSet current) {
		BitSet expanded=(BitSet)current.clone();
		for(MemoOptimizer.Check check:checks)
			if(intersects(check.owners(),current)) for(int owner:check.owners()) expanded.set(owner);
		for(MemoOptimizer.CostTerm cost:costs)
			if(intersects(cost.owners(),current)) for(int owner:cost.owners()) expanded.set(owner);
		return expanded;
	}

	private BitSet[] conditionalDomains(int[] selected, BitSet region) {
		BitSet[] result=copyDomains(proofDomains);
		for(int owner=0; owner<sizes.length; owner++)
			if(!region.get(owner) && selected[owner]>=0) {
				result[owner].clear(); result[owner].set(selected[owner]);
			}
		return result;
	}

	private SearchResult search(BitSet[] restrictions) {
		long remaining=workLimit-evaluations;
		long estimate=kernelWorkEstimate(restrictions,remaining);
		if(estimate>=0) {
			evaluations+=estimate;
			boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
			MemoOptimizer.DirectResult direct=new MemoOptimizer(sizes,coverage,nativeCosts,checks,costs,
				MemoOptimizer.Limits.exactDefaults()).optimizeDirect(restrictions,resources,remaining);
			if(direct.completed()) {
				SearchResult result=new SearchResult(); result.status=SearchStatus.COMPLETE;
				result.selected=direct.result().selectedRules(); result.cost=direct.result().cost();
				result.proof=direct.result();
				return result;
			}
			restrictions=direct.domains();
		}
		return enumerate(restrictions);
	}

	private SearchResult enumerate(BitSet[] restrictions) {
		SearchResult result=new SearchResult();
		result.status=SearchStatus.COMPLETE;
		int[] selected=new int[sizes.length]; Arrays.fill(selected,-1);
		if(sizes.length==0) {
			MemoOptimizer.Truth truth=fullTruth(selected);
			if(truth==MemoOptimizer.Truth.UNKNOWN) result.unknown=true;
			if(truth==MemoOptimizer.Truth.YES) { result.cost=fullCost(selected); result.selected=selected; }
			return result;
		}
		int owner=0;
		while(owner>=0) {
			int rule=restrictions[owner].nextSetBit(selected[owner]+1);
			if(rule<0) {
				selected[owner]=-1; owner--; continue;
			}
			if(!consume()) { result.status=SearchStatus.LIMITED; break; }
			selected[owner]=rule;
			if(!closedChecksAllow(owner,selected,result)) continue;
			if(owner+1<sizes.length) { selected[++owner]=-1; continue; }
			MemoOptimizer.Truth truth=fullTruth(selected);
			if(truth==MemoOptimizer.Truth.UNKNOWN) result.unknown=true;
			if(truth!=MemoOptimizer.Truth.YES) continue;
			BigDecimal value=fullCost(selected);
			if(result.cost==null || value.compareTo(result.cost)<0 || value.compareTo(result.cost)==0 &&
				lexicographicallyLess(selected,result.selected)) {
				result.cost=value; result.selected=selected.clone();
			}
		}
		return result;
	}

	/** n times the Cartesian-domain size bounds a complete variable-by-tuple scan. */
	private long kernelWorkEstimate(BitSet[] restrictions, long remaining) {
		long estimate=Math.max(1,sizes.length);
		for(BitSet domain:restrictions) {
			long cardinality=domain.cardinality();
			if(cardinality==0) return 0;
			if(estimate>remaining/cardinality) return -1;
			estimate*=cardinality;
		}
		return estimate;
	}

	private boolean closedChecksAllow(int owner, int[] selected, SearchResult result) {
		for(MemoOptimizer.Check check:checks) {
			int[] scope=check.owners();
			if(contains(scope,owner) && assigned(scope,selected)) {
				MemoOptimizer.Truth truth=check.evaluate(project(scope,selected));
				if(truth==MemoOptimizer.Truth.UNKNOWN) result.unknown=true;
				if(truth!=MemoOptimizer.Truth.YES) return false;
			}
		}
		return true;
	}

	private boolean fullyFeasible(int[] selected) {
		return fullTruth(selected) == MemoOptimizer.Truth.YES;
	}

	private MemoOptimizer.Truth fullTruth(int[] selected) {
		boolean unknown=false;
		for(MemoOptimizer.Check check:checks) {
			MemoOptimizer.Truth truth=check.evaluate(project(check.owners(),selected));
			if(truth==MemoOptimizer.Truth.NO) return truth;
			unknown |= truth==MemoOptimizer.Truth.UNKNOWN;
		}
		return unknown ? MemoOptimizer.Truth.UNKNOWN : MemoOptimizer.Truth.YES;
	}

	private BigDecimal fullCost(int[] selected) {
		BigDecimal result=BigDecimal.ZERO;
		for(int owner=0; owner<sizes.length; owner++)
			result=result.add(exact(nativeCosts[owner][selected[owner]]));
		for(MemoOptimizer.CostTerm cost:costs)
			result=result.add(cost.evaluateExact(project(cost.owners(),selected)));
		return result;
	}

	private MemoOptimizer.Result exactResult(SearchResult search) {
		if(search.proof!=null) return search.proof;
		MemoOptimizer.Status status=search.unknown ? MemoOptimizer.Status.UNRESOLVED
			: search.selected==null ? MemoOptimizer.Status.INFEASIBLE : MemoOptimizer.Status.OPTIMAL;
		return new MemoOptimizer.Result(status,search.cost,search.selected,emptyStatistics());
	}

	private Result result(int[] selected, BigDecimal cost, MemoOptimizer.Result proof, String reason) {
		return new Result(selected,cost,evaluations,repairs,proof,reason);
	}

	private boolean consume() {
		if(evaluations>=workLimit) return false;
		evaluations++;
		return true;
	}

	private void validateScopes() {
		for(MemoOptimizer.Check check:checks) validateScope(check.owners());
		for(MemoOptimizer.CostTerm cost:costs) validateScope(cost.owners());
	}

	private void validateScope(int[] scope) {
		for(int owner:scope)
			if(owner<0 || owner>=sizes.length)
				throw new IllegalArgumentException("Unknown owner " + owner);
	}

	private static double[][] copyAndValidateNative(double[][] source, int[] sizes) {
		if(source.length!=sizes.length) throw new IllegalArgumentException("Native cost owner mismatch");
		double[][] result=new double[source.length][];
		for(int owner=0; owner<source.length; owner++) {
			if(sizes[owner]<0 || source[owner].length!=sizes[owner])
				throw new IllegalArgumentException("Native cost domain mismatch");
			result[owner]=source[owner].clone();
		}
		return result;
	}

	private static BitSet[] copyAndValidateDomains(BitSet[] source, int[] sizes) {
		if(source!=null && source.length!=sizes.length)
			throw new IllegalArgumentException("Proof domain owner mismatch");
		BitSet[] result=new BitSet[sizes.length];
		for(int owner=0; owner<sizes.length; owner++) {
			if(source==null) {
				result[owner]=new BitSet(sizes[owner]); result[owner].set(0,sizes[owner]);
			}
			else {
				if(source.length!=sizes.length || source[owner]==null)
					throw new IllegalArgumentException("Proof domain owner mismatch");
				result[owner]=(BitSet)source[owner].clone();
				if(result[owner].length()>sizes[owner])
					throw new IllegalArgumentException("Proof domain contains an unknown rule");
			}
		}
		return result;
	}

	private static long estimatedWorkspace(int[] sizes, double[][] nativeCosts, BitSet[] domains) {
		long bytes=MemoResourcePolicy.checkedAdd(128,
			MemoResourcePolicy.checkedMultiply(sizes.length,48));
		for(int owner=0;owner<sizes.length;owner++) {
			long nativeLength=owner<nativeCosts.length && nativeCosts[owner]!=null ? nativeCosts[owner].length : 0;
			bytes=MemoResourcePolicy.checkedAdd(bytes,
				MemoResourcePolicy.checkedAdd(16,MemoResourcePolicy.checkedMultiply(nativeLength,8)));
			long words=(Math.max(0,sizes[owner])+63L)/64L;
			// Proof, conditional-repair and traversal domain snapshots may coexist.
			bytes=MemoResourcePolicy.checkedAdd(bytes,
				MemoResourcePolicy.checkedMultiply(3,MemoResourcePolicy.checkedAdd(32,
					MemoResourcePolicy.checkedMultiply(words,8))));
		}
		return bytes;
	}

	private static long estimatedImproveWorkspace(int[] sizes,double[][] nativeCosts,BitSet[] domains,
		int[] initialSelected,List<int[]> scopes) {
		long bytes=estimatedWorkspace(sizes,nativeCosts,domains);
		bytes=MemoResourcePolicy.checkedAdd(bytes,
			MemoResourcePolicy.checkedAdd(16,MemoResourcePolicy.checkedMultiply(initialSelected.length,4)));
		for(int[] scope:scopes)
			bytes=MemoResourcePolicy.checkedAdd(bytes,
				MemoResourcePolicy.checkedAdd(32,MemoResourcePolicy.checkedMultiply(scope.length,4)));
		return bytes;
	}

	private static BitSet[] copyDomains(BitSet[] source) {
		return Arrays.stream(source).map(bits -> (BitSet)bits.clone()).toArray(BitSet[]::new);
	}

	private static boolean sameDomains(BitSet[] left, BitSet[] right) {
		return Arrays.equals(left,right);
	}

	private static boolean assigned(int[] scope, int[] selected) {
		for(int owner:scope) if(selected[owner]<0) return false;
		return true;
	}

	private static boolean contains(int[] values, int value) {
		for(int candidate:values) if(candidate==value) return true;
		return false;
	}

	private static boolean intersects(int[] values, BitSet set) {
		for(int value:values) if(set.get(value)) return true;
		return false;
	}

	private static int[] project(int[] owners, int[] selected) {
		int[] result=new int[owners.length];
		for(int i=0;i<owners.length;i++) result[i]=selected[owners[i]];
		return result;
	}

	private static boolean lexicographicallyLess(int[] left, int[] right) {
		if(right==null) return true;
		for(int i=0;i<left.length;i++)
			if(left[i]!=right[i]) return left[i]<right[i];
		return false;
	}

	private static BigDecimal exact(double value) {
		if(!Double.isFinite(value)) throw new IllegalArgumentException("Costs must be finite: " + value);
		return new BigDecimal(value);
	}

	private static MemoOptimizer.Statistics emptyStatistics() {
		return new MemoOptimizer.Statistics(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
	}
}

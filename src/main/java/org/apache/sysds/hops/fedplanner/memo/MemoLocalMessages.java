/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;

/**
 * Incremental min-sum messages for the certificate-first local controller.
 * Each step eliminates one original owner and publishes a message only after
 * the complete table has been computed.  The active factors therefore always
 * form a sound relaxation of the original model.  Conditional bound queries
	 * intentionally ignore restrictions on an already eliminated owner.  Such a
	 * query remains a relaxation; callers validate a reconstructed assignment
	 * against the desired state and original model before closing that state.
 */
final class MemoLocalMessages {
	private static final long CHOICE_BYTES=Integer.BYTES;

	record Step(boolean changed,int owner,long cells,int[] scope,int[] originalOwners,String reason) {
		Step { scope=scope.clone(); originalOwners=originalOwners.clone(); }
		@Override public int[] scope() { return scope.clone(); }
		@Override public int[] originalOwners() { return originalOwners.clone(); }
	}
	record Statistics(long completedBuckets,long evaluatedCells,long costEvaluations,long checkEvaluations,
		long boundQueries,long memoryRejections,long candidatePreparations,long touchedVariables,
		long touchedOriginalFactors) { }

	private enum Kind { NATIVE, COST, CHECK, MESSAGE }
	private final int[] sizes;
	private final double[][] nativeCosts;
	private final List<Check> checks;
	private final List<CostTerm> costs;
	private final BitSet[] root;
	private final MemoResourcePolicy resources;
	private final List<Factor> active=new ArrayList<>();
	private final List<Factor>[] incident;
	private final long[] estimateCells;
	private final int[] estimateWidths;
	private final boolean[] estimateValid;
	private final List<Message> messages=new ArrayList<>();
	private final boolean[] eliminated;
	private final BitSet touched=new BitSet();
	private long retainedBytes,completedBuckets,evaluatedCells,costEvaluations,checkEvaluations,boundQueries,memoryRejections;
	private long candidatePreparations,touchedOriginalFactors;

	MemoLocalMessages(int[] sizes,double[][] nativeCosts,List<Check> checks,List<CostTerm> costs,
		BitSet[] rootDomains,MemoResourcePolicy resources) {
		validateClasses(sizes,checks,costs);
		this.sizes=Objects.requireNonNull(sizes).clone();
		this.nativeCosts=copyNative(nativeCosts,this.sizes);
		this.checks=List.copyOf(checks);
		this.costs=List.copyOf(costs);
		this.root=copyDomains(rootDomains,this.sizes);
		this.resources=Objects.requireNonNull(resources);
		eliminated=new boolean[sizes.length];
		incident=incidentLists(sizes.length);
		estimateCells=new long[sizes.length]; estimateWidths=new int[sizes.length]; estimateValid=new boolean[sizes.length];
		for(int owner=0;owner<sizes.length;owner++)
			active.add(nativeFactor(owner,nativeClasses(owner)));
		for(int id=0;id<costs.size();id++) {
			CostTerm term=costs.get(id); validateScope(term.owners());
			active.add(originalFactor(Kind.COST,term.owners(),term.certifiedCostClasses(),id));
		}
		for(int id=0;id<checks.size();id++) {
			Check check=checks.get(id); validateScope(check.owners());
			active.add(originalFactor(Kind.CHECK,check.owners(),check.certifiedTruthClasses(),id));
		}
		for(Factor factor:active) for(int owner:factor.owners) incident[owner].add(factor);
	}

	/** Conservative metadata estimate that a controller can admit before construction. */
	static long estimatedSetupBytes(int[] sizes,List<Check> checks,List<CostTerm> costs) {
		long bytes=MemoResourcePolicy.checkedMultiply(sizes.length,256);
		for(int size:sizes) bytes=MemoResourcePolicy.checkedAdd(bytes,
			MemoResourcePolicy.checkedMultiply(size,Long.BYTES));
		for(Check check:checks) bytes=MemoResourcePolicy.checkedAdd(bytes,
			MemoResourcePolicy.checkedMultiply(check.owners().length,96));
		for(CostTerm cost:costs) bytes=MemoResourcePolicy.checkedAdd(bytes,
			MemoResourcePolicy.checkedMultiply(cost.owners().length,96));
		return bytes;
	}
	static void validateClasses(int[] sizes,List<Check> checks,List<CostTerm> costs) {
		Objects.requireNonNull(sizes); Objects.requireNonNull(checks); Objects.requireNonNull(costs);
		for(Check check:checks) validateClasses(sizes,check.owners(),check.certifiedTruthClasses());
		for(CostTerm cost:costs) validateClasses(sizes,cost.owners(),cost.certifiedCostClasses());
	}
	private static void validateClasses(int[] sizes,int[] owners,int[][] classes) {
		for(int owner:owners) if(owner<0 || owner>=sizes.length) throw new IllegalArgumentException("Invalid owner");
		if(classes==null) return;
		if(classes.length!=owners.length) throw new IllegalArgumentException("Certified class axis mismatch");
		for(int axis=0;axis<owners.length;axis++)
			if(classes[axis]==null || classes[axis].length!=sizes[owners[axis]])
				throw new IllegalArgumentException("Certified class domain mismatch");
	}

	BigDecimal initialBound() { return lowerBound(root); }

	BigDecimal lowerBound(BitSet[] residualDomains) {
		BitSet[] residual=copyResidual(residualDomains);
		boundQueries++;
		for(BitSet domain:residual) if(domain.isEmpty()) return null;
		BigDecimal total=BigDecimal.ZERO;
		for(Factor factor:active) {
			BigDecimal minimum=factor.minimum(residual);
			if(minimum==null) return null;
			total=total.add(minimum);
		}
		return total;
	}

	Step refine(long maxCells) {
		return refine(maxCells,resources);
	}

	Step refine(long maxCells,MemoResourcePolicy currentResources) {
		if(maxCells<1) throw new IllegalArgumentException("Message cell budget must be positive");
		Objects.requireNonNull(currentResources);
		Estimate best=null;
		for(int owner=0;owner<sizes.length;owner++) if(!eliminated[owner]) {
			Estimate estimate=estimate(owner);
			if(estimate.cells<=maxCells && (best==null || estimate.betterThan(best))) best=estimate;
		}
		if(best==null)
			return new Step(false,-1,0,new int[0],new int[0],complete() ? "COMPLETE":"NO_ELIGIBLE");
		long metadata=estimateCandidateBytes(best.owner);
		if(!currentResources.admit(retainedBytes,metadata)) {
			memoryRejections++;
			return new Step(false,best.owner,best.cells,scope(best.owner),new int[0],"MEMORY_REJECTED");
		}
		Candidate candidate;
		try { candidate=prepare(best.owner); candidatePreparations++; }
		catch(OutOfMemoryError failure) { memoryRejections++; return new Step(false,best.owner,best.cells,
			scope(best.owner),new int[0],"ALLOCATION_FAILED"); }
		if(candidate.cells>maxCells)
			throw new IllegalStateException("Certified class estimate changed during materialization");
		long additional=estimateMessageBytes(candidate);
		if(!currentResources.admit(MemoResourcePolicy.checkedAdd(retainedBytes,metadata),additional)) {
			memoryRejections++;
			return new Step(false,candidate.owner,candidate.cells,candidate.separator,candidate.originalOwners,"MEMORY_REJECTED");
		}
		Message message=solve(candidate);
		if(message==null)
			return new Step(false,candidate.owner,candidate.cells,candidate.separator,candidate.originalOwners,"ALLOCATION_FAILED");
		active.removeAll(candidate.inputs);
		BitSet affected=new BitSet();
		for(Factor factor:candidate.inputs) {
			if(factor.kind!=Kind.MESSAGE) touchedOriginalFactors++;
			for(int owner:factor.owners) {
				incident[owner].remove(factor);
				affected.set(owner);
			}
		}
		active.add(message.factor);
		for(int owner:message.factor.owners) { incident[owner].add(message.factor); affected.set(owner); }
		for(int owner=affected.nextSetBit(0);owner>=0;owner=affected.nextSetBit(owner+1)) estimateValid[owner]=false;
		messages.add(message);
		eliminated[candidate.owner]=true;
		for(int owner:candidate.originalOwners) touched.set(owner);
		retainedBytes=MemoResourcePolicy.checkedAdd(retainedBytes,MemoResourcePolicy.checkedAdd(metadata,additional));
		completedBuckets++;
		evaluatedCells=MemoResourcePolicy.checkedAdd(evaluatedCells,candidate.cells);
		return new Step(true,candidate.owner,candidate.cells,candidate.separator,candidate.originalOwners,"COMPLETED");
	}

	boolean[] eliminated() { return eliminated.clone(); }
	BitSet activeOwners() {
		BitSet result=new BitSet();
		for(int owner=0;owner<eliminated.length;owner++) if(!eliminated[owner]) result.set(owner);
		return result;
	}
	boolean complete() { return activeOwners().isEmpty(); }
	long retainedBytes() { return retainedBytes; }
	Statistics statistics() { return new Statistics(completedBuckets,evaluatedCells,costEvaluations,
		checkEvaluations,boundQueries,memoryRejections,candidatePreparations,touched.cardinality(),touchedOriginalFactors); }

	int[] reconstruct(int[] preferred) {
		if(preferred==null || preferred.length!=sizes.length) throw new IllegalArgumentException("Invalid assignment");
		int[] selected=preferred.clone();
		for(int owner=0;owner<sizes.length;owner++)
			if(selected[owner]<0 || selected[owner]>=sizes[owner] || !root[owner].get(selected[owner])) return null;
		for(Factor factor:active) if(factor.kind==Kind.MESSAGE && !factor.message.restore(selected)) return null;
		return selected;
	}

	private Candidate prepare(int owner) {
		List<Factor> inputs=new ArrayList<>(incident[owner]);
		BitSet scope=new BitSet(),originals=new BitSet();
		for(Factor factor:inputs) {
			for(int axis:factor.owners) scope.set(axis);
			for(int axis:factor.originalOwners) originals.set(axis);
		}
		scope.clear(owner);
		int[] separator=scope.stream().toArray();
		int[][] mappings=new int[separator.length][];
		int[][] representatives=new int[separator.length][];
		long rows=1;
		for(int axis=0;axis<separator.length;axis++) {
			mappings[axis]=refineClasses(separator[axis],inputs);
			representatives[axis]=representatives(separator[axis],mappings[axis]);
			rows=product(rows,representatives[axis].length);
		}
		int[] ownerMapping=refineClasses(owner,inputs);
		int[] ownerRepresentatives=representatives(owner,ownerMapping);
		return new Candidate(owner,List.copyOf(inputs),separator,mappings,representatives,ownerRepresentatives,
			rows,product(rows,ownerRepresentatives.length),originals.stream().toArray());
	}

	private Estimate estimate(int owner) {
		if(estimateValid[owner]) return new Estimate(owner,estimateCells[owner],estimateWidths[owner]);
		BitSet scope=new BitSet();
		for(Factor factor:incident[owner]) for(int axis:factor.owners) scope.set(axis);
		scope.clear(owner);
		long cells=classCount(owner,incident[owner]);
		for(int axis=scope.nextSetBit(0);axis>=0;axis=scope.nextSetBit(axis+1))
			cells=product(cells,classCount(axis,incident[owner]));
		estimateCells[owner]=cells; estimateWidths[owner]=scope.cardinality(); estimateValid[owner]=true;
		return new Estimate(owner,cells,scope.cardinality());
	}
	private int[] scope(int owner) {
		BitSet scope=new BitSet();
		for(Factor factor:incident[owner]) for(int axis:factor.owners) scope.set(axis);
		scope.clear(owner); return scope.stream().toArray();
	}

	private int classCount(int owner,List<Factor> factors) {
		int count=0;
		for(int value=root[owner].nextSetBit(0);value>=0;value=root[owner].nextSetBit(value+1)) {
			boolean seen=false;
			for(int prior=root[owner].nextSetBit(0);prior>=0 && prior<value && !seen;prior=root[owner].nextSetBit(prior+1)) {
				seen=true;
				for(Factor factor:factors) {
					int axis=factor.axis(owner);
					if(axis>=0 && factor.classes[axis][value]!=factor.classes[axis][prior]) { seen=false; break; }
				}
			}
			if(!seen) count++;
		}
		return count;
	}

	private long estimateCandidateBytes(int owner) {
		BitSet scope=new BitSet(),originals=new BitSet();
		for(Factor factor:incident[owner]) {
			for(int axis:factor.owners) scope.set(axis);
			for(int axis:factor.originalOwners) originals.set(axis);
		}
		scope.clear(owner);
		long bytes=MemoResourcePolicy.checkedMultiply(incident[owner].size(),8);
		for(int axis=scope.nextSetBit(0);axis>=0;axis=scope.nextSetBit(axis+1))
			bytes=MemoResourcePolicy.checkedAdd(bytes,MemoResourcePolicy.checkedMultiply(sizes[axis],8));
		bytes=MemoResourcePolicy.checkedAdd(bytes,MemoResourcePolicy.checkedMultiply(sizes[owner],8));
		bytes=MemoResourcePolicy.checkedAdd(bytes,MemoResourcePolicy.checkedMultiply(originals.cardinality(),4));
		return MemoResourcePolicy.checkedAdd(bytes,256);
	}

	private Message solve(Candidate candidate) {
		if(candidate.rows>Integer.MAX_VALUE-8L) return null;
		BigDecimal[] values;
		int[] choices;
		try {
			values=new BigDecimal[(int)candidate.rows];
			choices=new int[(int)candidate.rows];
		}
		catch(OutOfMemoryError failure) { return null; }
		int[] assignment=new int[sizes.length]; Arrays.fill(assignment,-1);
		for(int row=0;row<values.length;row++) {
			selectRow(row,candidate.separator,candidate.representatives,assignment);
			BigDecimal best=null; int choice=-1;
			for(int value:candidate.ownerRepresentatives) {
				assignment[candidate.owner]=value;
				BigDecimal sum=BigDecimal.ZERO; boolean allowed=true;
				for(Factor input:candidate.inputs) {
					BigDecimal contribution=input.exact(assignment);
					if(contribution==null) { allowed=false; break; }
					sum=sum.add(contribution);
				}
				if(allowed && (best==null || sum.compareTo(best)<0 || sum.compareTo(best)==0 && value<choice)) {
					best=sum; choice=value;
				}
			}
			values[row]=best; choices[row]=choice;
		}
		return new Message(candidate,values,choices);
	}

	private final class Factor {
		private final Kind kind;
		private final int[] owners;
		private final int[][] classes;
		private final int id;
		private final Message message;
		private final int[] originalOwners;
		private final int[] evaluationOwners,fixedValues;
		private boolean constantComputed;
		private BigDecimal constantValue;
		private Factor(Kind kind,int[] owners,int[][] classes,int id,Message message,int[] originalOwners,
			int[] evaluationOwners,int[] fixedValues) {
			this.kind=kind; this.owners=owners.clone(); this.classes=copyClasses(classes); this.id=id;
			this.message=message; this.originalOwners=originalOwners.clone();
			this.evaluationOwners=evaluationOwners.clone(); this.fixedValues=fixedValues.clone();
		}
		private int axis(int owner) { for(int axis=0;axis<owners.length;axis++) if(owners[axis]==owner) return axis; return -1; }
		private BigDecimal minimum(BitSet[] domains) {
			if(kind==Kind.COST) return owners.length==0 ? constant():costs.get(id).certifiedMinimum();
			if(kind==Kind.CHECK) return owners.length==0 ? constant():BigDecimal.ZERO;
			if(kind==Kind.NATIVE) {
				if(owners.length==0) return constant();
				BigDecimal best=null;
				for(int value=domains[id].nextSetBit(0);value>=0;value=domains[id].nextSetBit(value+1)) {
					BigDecimal cost=MemoLocalMessages.exact(nativeCosts[id][value]);
					if(best==null || cost.compareTo(best)<0) best=cost;
				}
				return best;
			}
			return message.minimum(domains);
		}
		private BigDecimal constant() {
			if(!constantComputed) { constantValue=exact(new int[sizes.length]); constantComputed=true; }
			return constantValue;
		}
		private BigDecimal exact(int[] assignment) {
			if(kind==Kind.NATIVE) return MemoLocalMessages.exact(nativeCosts[id][fixedValues[0]>=0 ?
				fixedValues[0]:assignment[id]]);
			int[] tuple=new int[evaluationOwners.length];
			for(int axis=0;axis<tuple.length;axis++) tuple[axis]=fixedValues[axis]>=0 ? fixedValues[axis]
				:assignment[evaluationOwners[axis]];
			if(kind==Kind.COST) {
				costEvaluations++;
				BigDecimal value=costs.get(id).evaluateExact(tuple);
				if(value.compareTo(costs.get(id).certifiedMinimum())<0)
					throw new IllegalArgumentException("Cost below certified minimum");
				return value;
			}
			if(kind==Kind.CHECK) { checkEvaluations++; return checks.get(id).evaluate(tuple)==Truth.NO ? null : BigDecimal.ZERO; }
			return message.value(assignment);
		}
	}

	private Factor nativeFactor(int owner,int[] classes) {
		int representative=root[owner].nextSetBit(0); boolean invariant=true;
		BigDecimal value=MemoLocalMessages.exact(nativeCosts[owner][representative]);
		for(int ordinal=root[owner].nextSetBit(representative+1);ordinal>=0;ordinal=root[owner].nextSetBit(ordinal+1))
			if(value.compareTo(MemoLocalMessages.exact(nativeCosts[owner][ordinal]))!=0) { invariant=false; break; }
		return invariant
			? new Factor(Kind.NATIVE,new int[0],new int[0][],owner,null,new int[] {owner},new int[] {owner},
				new int[] {representative})
			: new Factor(Kind.NATIVE,new int[] {owner},new int[][] {classes},owner,null,new int[] {owner},
				new int[] {owner},new int[] {-1});
	}
	private Factor originalFactor(Kind kind,int[] owners,int[][] classes,int id) {
		int[][] proof=classes;
		if(proof!=null) {
			if(proof.length!=owners.length) throw new IllegalArgumentException("Certified class axis mismatch");
			for(int axis=0;axis<owners.length;axis++)
				if(proof[axis]==null || proof[axis].length!=sizes[owners[axis]])
					throw new IllegalArgumentException("Certified class domain mismatch");
		}
		else {
			proof=new int[owners.length][];
			for(int axis=0;axis<owners.length;axis++) {
				proof[axis]=new int[sizes[owners[axis]]];
				for(int ordinal=0;ordinal<proof[axis].length;ordinal++) proof[axis][ordinal]=ordinal;
			}
		}
		int[] fixed=new int[owners.length]; Arrays.fill(fixed,-1);
		int count=0;
		for(int axis=0;axis<owners.length;axis++) {
			int owner=owners[axis],representative=root[owner].nextSetBit(0);
			boolean invariant=root[owner].cardinality()==1;
			if(!invariant && classes!=null) {
				invariant=true; int label=classes[axis][representative];
				for(int ordinal=root[owner].nextSetBit(representative+1);ordinal>=0;
					ordinal=root[owner].nextSetBit(ordinal+1))
					if(classes[axis][ordinal]!=label) { invariant=false; break; }
			}
			if(invariant) fixed[axis]=representative; else count++;
		}
		int[] reduced=new int[count]; int[][] reducedProof=new int[count][];
		for(int source=0,target=0;source<owners.length;source++) if(fixed[source]<0) {
			reduced[target]=owners[source]; reducedProof[target]=proof[source]; target++;
		}
		return new Factor(kind,reduced,reducedProof,id,null,owners,owners,fixed);
	}

	private final class Message {
		private final int owner;
		private final int[] separator;
		private final int[][] mappings,representatives;
		private final BigDecimal[] values;
		private final int[] choices;
		private final List<Factor> inputs;
		private final Factor factor;
		private Message(Candidate candidate,BigDecimal[] values,int[] choices) {
			owner=candidate.owner; separator=candidate.separator; mappings=candidate.mappings;
			representatives=candidate.representatives; this.values=values; this.choices=choices;
			inputs=candidate.inputs;
			int count=0; for(int axis=0;axis<separator.length;axis++) if(representatives[axis].length>1) count++;
			int[] reduced=new int[count]; int[][] reducedMappings=new int[count][];
			for(int source=0,target=0;source<separator.length;source++) if(representatives[source].length>1) {
				reduced[target]=separator[source]; reducedMappings[target]=mappings[source]; target++;
			}
			factor=new Factor(Kind.MESSAGE,reduced,reducedMappings,-1,this,candidate.originalOwners,new int[0],new int[0]);
		}
		private int index(int[] assignment) {
			int index=0;
			for(int axis=0;axis<separator.length;axis++) {
				int ordinal=assignment[separator[axis]];
				if(ordinal<0 && representatives[axis].length==1) ordinal=representatives[axis][0];
				if(ordinal<0) return -1;
				int label=mappings[axis][ordinal];
				if(label<0) return -1;
				index=index*representatives[axis].length+label;
			}
			return index;
		}
		private BigDecimal value(int[] assignment) { int row=index(assignment); return row<0 ? null : values[row]; }
		private BigDecimal minimum(BitSet[] domains) {
			BigDecimal best=null;
			for(int row=0;row<values.length;row++) if(values[row]!=null && compatible(row,domains))
				if(best==null || values[row].compareTo(best)<0) best=values[row];
			return best;
		}
		private boolean compatible(int row,BitSet[] domains) {
			for(int axis=separator.length-1;axis>=0;axis--) {
				int count=representatives[axis].length,label=row%count; row/=count;
				boolean found=false;
				for(int value=domains[separator[axis]].nextSetBit(0);value>=0;value=domains[separator[axis]].nextSetBit(value+1))
					if(mappings[axis][value]==label) { found=true; break; }
				if(!found) return false;
			}
			return true;
		}
		private boolean restore(int[] selected) {
			int row=index(selected);
			if(row<0 || values[row]==null || choices[row]<0) return false;
			selected[owner]=choices[row];
			for(Factor input:inputs) if(input.kind==Kind.MESSAGE && !input.message.restore(selected)) return false;
			return true;
		}
	}

	private final class Candidate {
		private final int owner;
		private final List<Factor> inputs;
		private final int[] separator;
		private final int[][] mappings,representatives;
		private final int[] ownerRepresentatives,originalOwners;
		private final long rows,cells;
		private Candidate(int owner,List<Factor> inputs,int[] separator,int[][] mappings,
			int[][] representatives,int[] ownerRepresentatives,long rows,long cells,int[] originalOwners) {
			this.owner=owner; this.inputs=inputs; this.separator=separator; this.mappings=mappings;
			this.representatives=representatives; this.ownerRepresentatives=ownerRepresentatives;
			this.rows=rows; this.cells=cells; this.originalOwners=originalOwners;
		}
	}
	private record Estimate(int owner,long cells,int width) {
		private boolean betterThan(Estimate other) {
			if(cells!=other.cells) return cells<other.cells;
			if(width!=other.width) return width<other.width;
			return owner<other.owner;
		}
	}

	private int[] refineClasses(int owner,List<Factor> factors) {
		List<int[]> partitions=new ArrayList<>();
		for(Factor factor:factors) { int axis=factor.axis(owner); if(axis>=0) partitions.add(factor.classes[axis]); }
		int[] result=new int[sizes[owner]]; Arrays.fill(result,-1);
		Map<IntKey,Integer> labels=new LinkedHashMap<>();
		for(int value=root[owner].nextSetBit(0);value>=0;value=root[owner].nextSetBit(value+1)) {
			int[] tuple=new int[partitions.size()];
			for(int i=0;i<tuple.length;i++) tuple[i]=partitions.get(i)[value];
			result[value]=labels.computeIfAbsent(new IntKey(tuple),ignored -> labels.size());
		}
		return result;
	}
	private int[] representatives(int owner,int[] mapping) {
		int count=0; for(int value=root[owner].nextSetBit(0);value>=0;value=root[owner].nextSetBit(value+1))
			count=Math.max(count,mapping[value]+1);
		int[] result=new int[count]; Arrays.fill(result,-1);
		for(int value=root[owner].nextSetBit(0);value>=0;value=root[owner].nextSetBit(value+1))
			if(result[mapping[value]]<0) result[mapping[value]]=value;
		return result;
	}
	private int[] nativeClasses(int owner) {
		int[] result=new int[sizes[owner]]; Map<BigDecimal,Integer> labels=new LinkedHashMap<>();
		for(int value=0;value<sizes[owner];value++)
			result[value]=labels.computeIfAbsent(exact(nativeCosts[owner][value]),ignored -> labels.size());
		return result;
	}
	private BitSet[] copyResidual(BitSet[] domains) {
		if(domains==null || domains.length!=sizes.length) throw new IllegalArgumentException("Invalid domains");
		BitSet[] result=new BitSet[domains.length];
		for(int owner=0;owner<sizes.length;owner++) {
			result[owner]=(BitSet)Objects.requireNonNull(domains[owner]).clone();
			if(result[owner].length()>sizes[owner]) throw new IllegalArgumentException("Invalid domain");
			BitSet outside=(BitSet)result[owner].clone(); outside.andNot(root[owner]);
			if(!outside.isEmpty()) throw new IllegalArgumentException("Residual domain widens the root domain");
			result[owner].and(root[owner]);
		}
		return result;
	}
	private static void selectRow(int row,int[] scope,int[][] representatives,int[] assignment) {
		for(int axis=scope.length-1;axis>=0;axis--) {
			int[] values=representatives[axis]; assignment[scope[axis]]=values[row%values.length]; row/=values.length;
		}
	}
	private void validateScope(int[] scope) {
		for(int owner:scope) if(owner<0 || owner>=sizes.length) throw new IllegalArgumentException("Invalid owner");
	}
	private static long product(long left,int right) {
		return right!=0 && left>Long.MAX_VALUE/right ? Long.MAX_VALUE : left*right;
	}
	private long estimateMessageBytes(Candidate candidate) {
		// BigDecimal values can retain arbitrary precision decimal state.  Use the
		// same deliberately conservative per-cell policy as other optional tables.
		long bytes=MemoResourcePolicy.checkedMultiply(candidate.rows,768+CHOICE_BYTES);
		for(int owner:candidate.separator) {
			// Candidate mappings are already admitted; the output factor retains one copy.
			bytes=MemoResourcePolicy.checkedAdd(bytes,MemoResourcePolicy.checkedMultiply(sizes[owner],4));
		}
		return MemoResourcePolicy.checkedAdd(bytes,256);
	}
	@SuppressWarnings("unchecked")
	private List<Factor>[] incidentLists(int count) {
		List<Factor>[] result=(List<Factor>[])new List<?>[count];
		for(int i=0;i<count;i++) result[i]=new ArrayList<>();
		return result;
	}
	private static BigDecimal exact(double value) {
		if(!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite cost");
		return new BigDecimal(value);
	}
	private static double[][] copyNative(double[][] values,int[] sizes) {
		if(values==null || values.length!=sizes.length) throw new IllegalArgumentException("Invalid native costs");
		double[][] result=new double[values.length][];
		for(int owner=0;owner<values.length;owner++) {
			if(sizes[owner]<1 || values[owner]==null || values[owner].length!=sizes[owner])
				throw new IllegalArgumentException("Invalid domain");
			result[owner]=values[owner].clone(); for(double value:result[owner]) exact(value);
		}
		return result;
	}
	private static BitSet[] copyDomains(BitSet[] domains,int[] sizes) {
		if(domains==null || domains.length!=sizes.length) throw new IllegalArgumentException("Invalid domains");
		BitSet[] result=new BitSet[domains.length];
		for(int owner=0;owner<domains.length;owner++) {
			result[owner]=(BitSet)Objects.requireNonNull(domains[owner]).clone();
			if(result[owner].isEmpty() || result[owner].length()>sizes[owner]) throw new IllegalArgumentException("Invalid domain");
		}
		return result;
	}
	private static int[][] copyClasses(int[][] classes) {
		if(classes==null) return null;
		return Arrays.stream(classes).map(int[]::clone).toArray(int[][]::new);
	}
	private record IntKey(int[] values) {
		private IntKey { values=values.clone(); }
		@Override public boolean equals(Object other) { return other instanceof IntKey key && Arrays.equals(values,key.values); }
		@Override public int hashCode() { return Arrays.hashCode(values); }
	}
}

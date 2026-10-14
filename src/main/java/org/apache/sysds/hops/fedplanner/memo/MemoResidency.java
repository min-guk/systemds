/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;

/**
 * Positive-only residency proof over immutable program facts.
 *
 * <p>This analysis deliberately forgets the provenance distinction between
 * PUBLIC and PRIVATE_AGGREGATE_TO_PUBLIC.  It may only answer that every
 * represented value is locally materializable; it never manufactures an exact
 * privacy value and a negative answer must not prune a rule. BOTTOM is the lack
 * of a payload derivation in the least fixed point, so a recursive component
 * needs a real privacy seed. Availability and missing-return legality remain
 * independent resolver/model obligations.</p>
 */
final class MemoResidency {
	private static final int BOTTOM = -1;
	private static final int RELEASED = 0;
	private static final int AGGREGATE_ONLY = 1;
	private static final int MAY_BE_PRIVATE = 2;

	private final MemoProgramFacts facts;
	private final Map<Integer,Privacy> sourcePrivacy;
	private final int bindingCount;
	private final int[] values;
	private final int[][] dependencies;
	private final List<List<Integer>> consumers;

	MemoResidency(MemoProgramFacts facts, Map<Integer,Privacy> sourcePrivacy) {
		this.facts=Objects.requireNonNull(facts);
		this.sourcePrivacy=Map.copyOf(Objects.requireNonNull(sourcePrivacy));
		bindingCount=facts.bindings().size();
		int size=bindingCount+facts.occurrences().size();
		values=new int[size]; Arrays.fill(values,BOTTOM);
		dependencies=new int[size][];
		consumers=new ArrayList<>(size);
		for(int i=0;i<size;i++) consumers.add(new ArrayList<>());
		indexDependencies();
		solve();
	}

	boolean provesReleasedBinding(int binding) {
		return binding>=0 && binding<bindingCount && values[binding]==RELEASED;
	}

	boolean provesReleasedOccurrence(int occurrence) {
		int node=occurrenceNode(occurrence);
		return node>=bindingCount && node<values.length && values[node]==RELEASED;
	}

	private void indexDependencies() {
		for(var binding : facts.bindings()) {
			List<Integer> deps=new ArrayList<>();
			switch(binding.kind()) {
				case DEFINITION -> {
					if(!binding.children().isEmpty()) deps.add(binding.children().get(0));
					else if(binding.referenceId()>=0 && binding.referenceId()<facts.occurrences().size())
						deps.add(occurrenceNode(binding.referenceId()));
				}
				case SELECT, LOOP_HEAD, LAST, CALL_ARG -> deps.addAll(binding.children());
				case FORMAL -> {
					for(var call : facts.callsForFunction(binding.referenceId()))
						if(binding.position()>=0 && binding.position()<call.actualBindingIds().size())
							deps.add(call.actualBindingIds().get(binding.position()));
				}
				case CALL_RETURN -> deps.addAll(binding.children());
				case CALL_COMPLETION, UNBOUND, MISSING -> { /* no payload dependency */ }
			}
			dependencies[binding.id()]=toArray(deps);
		}
		for(var occurrence : facts.occurrences()) {
			List<Integer> deps=new ArrayList<>();
			Hop hop=occurrence.hop();
			if(hop instanceof DataOp data && data.getOp()==OpOpData.TRANSIENTREAD) {
				int value=facts.valueBinding(occurrence.id());
				if(value>=0) deps.add(value);
			}
			else if(!(hop instanceof FunctionOp) && !isExternalSource(hop))
				for(int position=0;position<hop.getInput().size();position++) {
					int input=facts.inputBinding(occurrence.id(),position);
					if(input>=0) deps.add(input);
					else deps.add(-1);
				}
			dependencies[occurrenceNode(occurrence.id())]=toArray(deps);
		}
		for(int node=0;node<dependencies.length;node++)
			for(int dependency : dependencies[node]) if(dependency>=0 && dependency<values.length)
				consumers.get(dependency).add(node);
	}

	private void solve() {
		ArrayDeque<Integer> pending=new ArrayDeque<>();
		boolean[] queued=new boolean[values.length];
		for(int node=0;node<values.length;node++) { pending.add(node); queued[node]=true; }
		while(!pending.isEmpty()) {
			int node=pending.removeFirst(); queued[node]=false;
			int next=node<bindingCount ? evaluateBinding(node) : evaluateOccurrence(node-bindingCount);
			if(next<=values[node]) continue;
			values[node]=next;
			for(int consumer : consumers.get(node)) if(!queued[consumer]) {
				pending.addLast(consumer); queued[consumer]=true;
			}
		}
	}

	private int evaluateBinding(int id) {
		var binding=facts.binding(id);
		return switch(binding.kind()) {
			case LOOP_HEAD -> joinLoopHead(dependencies[id]);
			case DEFINITION, SELECT, LAST, CALL_ARG, FORMAL, CALL_RETURN ->
				joinRequiredDependencies(dependencies[id]);
			case UNBOUND -> MAY_BE_PRIVATE;
			case CALL_COMPLETION, MISSING -> MAY_BE_PRIVATE;
		};
	}

	private int evaluateOccurrence(int occurrenceId) {
		var occurrence=facts.occurrence(occurrenceId);
		Privacy declared=sourcePrivacy.get(occurrence.compiledOwnerId());
		if(declared!=null) return privacyClass(declared);
		Hop hop=occurrence.hop();
		if(isExternalSource(hop) || hop instanceof FunctionOp) return MAY_BE_PRIVATE;
		int[] deps=dependencies[occurrenceNode(occurrenceId)];
		int[] inputs=new int[deps.length];
		for(int i=0;i<deps.length;i++) {
			if(deps[i]<0 || deps[i]>=values.length) return MAY_BE_PRIVATE;
			inputs[i]=values[deps[i]];
		}
		return abstractTransfer(hop,inputs);
	}

	/** Package-private for exhaustive transfer soundness tests. */
	static int abstractTransfer(Hop hop,int... inputs) {
		Objects.requireNonNull(hop);
		for(int input : inputs) {
			if(input==BOTTOM) return BOTTOM;
			if(input==MAY_BE_PRIVATE) return MAY_BE_PRIVATE;
			if(input<RELEASED || input>AGGREGATE_ONLY)
				throw new IllegalArgumentException("Unknown residency class "+input);
		}
		if(inputs.length==0) return RELEASED;
		List<Privacy> representatives=new ArrayList<>(inputs.length);
		for(int input : inputs) representatives.add(input==AGGREGATE_ONLY
			? Privacy.PRIVATE_AGGREGATE : Privacy.PUBLIC);
		return privacyClass(FederatedPlannerUtils.derivePrivacyConstraint(hop,representatives));
	}

	/** A transfer certificate, not a replacement for the inputs' exact provenance. */
	static boolean preservesReleasedInputs(Hop hop) {
		return abstractTransfer(hop,new int[hop.getInput().size()])==RELEASED;
	}

	private int joinRequiredDependencies(int[] deps) {
		if(deps.length==0) return BOTTOM;
		int result=BOTTOM;
		for(int dependency : deps) {
			if(dependency<0 || dependency>=values.length) return MAY_BE_PRIVATE;
			result=Math.max(result,values[dependency]);
		}
		return result;
	}

	/** Payload join only; the resolver/model independently validates entry and backedge availability. */
	private int joinLoopHead(int[] deps) {
		if(deps.length==0) return BOTTOM;
		int result=BOTTOM;
		for(int dependency : deps) {
			if(dependency<0 || dependency>=values.length) return MAY_BE_PRIVATE;
			result=Math.max(result,values[dependency]);
		}
		return result;
	}

	private int occurrenceNode(int occurrence) { return bindingCount+occurrence; }
	private static int[] toArray(List<Integer> values) { return values.stream().mapToInt(Integer::intValue).toArray(); }
	private static boolean isExternalSource(Hop hop) {
		if(!(hop instanceof DataOp data)) return false;
		return data.getOp()==OpOpData.PERSISTENTREAD || data.getOp()==OpOpData.SQLREAD
			|| data.getOp()==OpOpData.FEDERATED;
	}
	private static int privacyClass(Privacy privacy) {
		return switch(privacy) {
			case PUBLIC, PRIVATE_AGGREGATE_TO_PUBLIC -> RELEASED;
			case PRIVATE_AGGREGATE -> AGGREGATE_ONLY;
			case PRIVATE -> MAY_BE_PRIVATE;
		};
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Predicate;

/**
 * Opt-in observations of the immutable Memo factor model.  These records are diagnostic only:
 * category removal is a graph projection and is never used to relax or solve the optimization.
 */
public final class MemoFactorDiagnostics {
	public static final String ENABLE_PROPERTY="sysds.fedplanner.memo.factorDiagnostics";
	private static final String MAX_FACTORS_PROPERTY=ENABLE_PROPERTY+".maxFactors";
	private static final String MAX_ELIMINATION_NODES_PROPERTY=ENABLE_PROPERTY+".maxEliminationNodes";
	private static final String MAX_GRAPH_PAIRS_PROPERTY=ENABLE_PROPERTY+".maxGraphPairs";
	private static final int DEFAULT_MAX_FACTORS=10_000;
	private static final int DEFAULT_MAX_ELIMINATION_NODES=2_000;
	private static final int DEFAULT_MAX_GRAPH_PAIRS=5_000_000;
	private static final long ROW_LIMIT=Long.MAX_VALUE;

	public enum Kind { CHECK, COST, NATIVE_ARRAY }
	public enum Category {
		NATIVE_UNARY,
		PRIVACY_UNARY,
		INPUT_SUPPLY,
		LOOP_BOUNDARY,
		MOVEMENT_RETAINED_SHARED,
		MOVEMENT_UNRETAINED,
		LOOP_ENTRY
	}

	public record Factor(int id,Kind kind,Category category,int site,int[] scope,int[] domains,
		List<String> tags,boolean evaluatorInstrumented,long evaluations,long evaluationNanos) {
		public Factor {
			scope=scope.clone(); domains=domains.clone(); tags=List.copyOf(tags);
		}
		@Override public int[] scope() { return scope.clone(); }
		@Override public int[] domains() { return domains.clone(); }
	}

	/** Width/row values are deterministic min-fill estimates, not an actual treewidth claim. */
	public record GraphSummary(String excludedCategory,int nodes,int factors,long edges,int components,
		int leafPeeled,int coreNodes,int estimatedMaxSeparatorVariables,long estimatedMaxTableRows,
		boolean graphSkipped,boolean eliminationSkipped) { }

	public record CategoryTiming(Category category,int factors,int instrumentedFactors,
		long evaluations,long evaluationNanos) { }

	private static final class MutableFactor {
		private final int id;
		private final Kind kind;
		private final Category category;
		private final int site;
		private final int[] scope;
		private final List<String> tags;
		private final boolean evaluatorInstrumented;
		private final LongAdder evaluations=new LongAdder();
		private final LongAdder nanos=new LongAdder();
		private MutableFactor(int id,Kind kind,Category category,int site,int[] scope,
			List<String> tags,boolean evaluatorInstrumented) {
			this.id=id; this.kind=kind; this.category=category; this.site=site;
			this.scope=scope.clone(); this.tags=List.copyOf(tags);
			this.evaluatorInstrumented=evaluatorInstrumented;
		}
		private Factor snapshot(int[] originalDomains) {
			int[] domains=new int[scope.length];
			for(int i=0;i<scope.length;i++) domains[i]=originalDomains[scope[i]];
			return new Factor(id,kind,category,site,scope,domains,tags,evaluatorInstrumented,
				evaluations.sum(),nanos.sum());
		}
	}

	private final int[] originalDomains;
	private final List<MutableFactor> factors=new ArrayList<>();
	private boolean structureEmitted;

	public MemoFactorDiagnostics(int[] originalDomains) {
		this(originalDomains,true);
		for(int domain:this.originalDomains)
			if(domain<1) throw new IllegalArgumentException("Factor diagnostics require positive domains");
	}

	/** Construction-only view used while MemoProgramModel fills its immutable domain vector. */
	static MemoFactorDiagnostics liveDomains(int[] originalDomains) {
		return new MemoFactorDiagnostics(originalDomains,false);
	}

	private MemoFactorDiagnostics(int[] originalDomains,boolean copy) {
		this.originalDomains=copy ? originalDomains.clone() : originalDomains;
	}

	public static boolean enabled() { return Boolean.getBoolean(ENABLE_PROPERTY); }

	public void addNativeArray(int owner,List<String> tags) {
		register(Kind.NATIVE_ARRAY,Category.NATIVE_UNARY,owner,new int[] {owner},tags,false);
	}

	public Check check(Category category,int site,int[] scope,Predicate predicate,int[][] truthClasses,
		List<String> tags) {
		MutableFactor factor=register(Kind.CHECK,category,site,scope,tags,true);
		Predicate timed=rules -> {
			long start=System.nanoTime();
			try { return predicate.test(rules); }
			finally { factor.evaluations.increment(); factor.nanos.add(System.nanoTime()-start); }
		};
		return new Check(site,scope,timed,truthClasses);
	}

	public MemoOptimizer.CostTerm cost(Category category,int[] scope,ToDoubleFunction<int[]> evaluator,
		double certifiedMinimum,int[][] costClasses,List<String> tags) {
		MutableFactor factor=register(Kind.COST,category,-1,scope,tags,true);
		ToDoubleFunction<int[]> timed=rules -> {
			long start=System.nanoTime();
			try { return evaluator.applyAsDouble(rules); }
			finally { factor.evaluations.increment(); factor.nanos.add(System.nanoTime()-start); }
		};
		return new MemoOptimizer.CostTerm(scope,timed,certifiedMinimum,costClasses);
	}

	private MutableFactor register(Kind kind,Category category,int site,int[] scope,List<String> tags,
		boolean instrumented) {
		MutableFactor result=new MutableFactor(factors.size(),kind,category,site,scope,tags,instrumented);
		factors.add(result);
		return result;
	}

	public List<Factor> factors() { return factors.stream().map(factor -> factor.snapshot(originalDomains)).toList(); }

	public List<CategoryTiming> timings() {
		EnumMap<Category,long[]> totals=new EnumMap<>(Category.class);
		for(Category category:Category.values()) totals.put(category,new long[4]);
		for(MutableFactor factor:factors) {
			long[] values=totals.get(factor.category);
			values[0]++; if(factor.evaluatorInstrumented) values[1]++;
			values[2]+=factor.evaluations.sum(); values[3]+=factor.nanos.sum();
		}
		return totals.entrySet().stream().map(entry -> new CategoryTiming(entry.getKey(),
			(int)entry.getValue()[0],(int)entry.getValue()[1],entry.getValue()[2],entry.getValue()[3])).toList();
	}

	public List<GraphSummary> graphSummaries() {
		List<GraphSummary> result=new ArrayList<>();
		result.add(graphSummary(null,null));
		for(Category category:Category.values()) result.add(graphSummary(category,null));
		Set<String> relationshipTags=new LinkedHashSet<>();
		for(MutableFactor factor:factors) for(String tag:factor.tags)
			if(tag.startsWith("relation:")) relationshipTags.add(tag);
		for(String tag:relationshipTags) result.add(graphSummary(null,tag));
		return List.copyOf(result);
	}

	private GraphSummary graphSummary(Category excluded,String excludedTag) {
		int included=0; long pairOperations=0;
		for(MutableFactor factor:factors) {
			if(factor.category==excluded || excludedTag!=null && factor.tags.contains(excludedTag)) continue;
			included++;
			long width=factor.scope.length;
			pairOperations=saturatedAdd(pairOperations,width*(width-1)/2);
		}
		String exclusion=excluded!=null ? "CATEGORY:"+excluded.name() : excludedTag!=null ? "TAG:"+excludedTag : "NONE";
		if(pairOperations>positiveInt(MAX_GRAPH_PAIRS_PROPERTY,DEFAULT_MAX_GRAPH_PAIRS))
			return new GraphSummary(exclusion,originalDomains.length,included,-1,-1,-1,-1,-1,-1,true,true);
		@SuppressWarnings("unchecked")
		Set<Integer>[] adjacency=new Set[originalDomains.length];
		for(int i=0;i<adjacency.length;i++) adjacency[i]=new LinkedHashSet<>();
		for(MutableFactor factor:factors) {
			if(factor.category==excluded || excludedTag!=null && factor.tags.contains(excludedTag)) continue;
			for(int i=0;i<factor.scope.length;i++) for(int j=i+1;j<factor.scope.length;j++) {
				int left=factor.scope[i],right=factor.scope[j];
				if(left!=right) { adjacency[left].add(right); adjacency[right].add(left); }
			}
		}
		long edges=0;
		for(Set<Integer> neighbors:adjacency) edges+=neighbors.size();
		edges/=2;
		int components=countComponents(adjacency);
		int peeled=leafPeel(adjacency);
		boolean skip=originalDomains.length>positiveInt(MAX_ELIMINATION_NODES_PROPERTY,DEFAULT_MAX_ELIMINATION_NODES);
		long[] estimate=skip ? new long[] {-1,-1} : eliminate(adjacency);
		return new GraphSummary(exclusion,originalDomains.length,included,
			edges,components,peeled,originalDomains.length-peeled,(int)estimate[0],estimate[1],false,skip);
	}

	private static int countComponents(Set<Integer>[] adjacency) {
		boolean[] seen=new boolean[adjacency.length];
		int count=0;
		for(int start=0;start<adjacency.length;start++) if(!seen[start]) {
			count++; ArrayDeque<Integer> pending=new ArrayDeque<>(); pending.add(start); seen[start]=true;
			while(!pending.isEmpty()) for(int next:adjacency[pending.removeFirst()])
				if(!seen[next]) { seen[next]=true; pending.addLast(next); }
		}
		return count;
	}

	private static int leafPeel(Set<Integer>[] source) {
		@SuppressWarnings("unchecked") Set<Integer>[] graph=new Set[source.length];
		int[] degrees=new int[source.length]; ArrayDeque<Integer> pending=new ArrayDeque<>();
		for(int i=0;i<source.length;i++) {
			graph[i]=new LinkedHashSet<>(source[i]); degrees[i]=graph[i].size();
			if(degrees[i]<=1) pending.add(i);
		}
		boolean[] removed=new boolean[source.length]; int result=0;
		while(!pending.isEmpty()) {
			int node=pending.removeFirst();
			if(removed[node] || degrees[node]>1) continue;
			removed[node]=true; result++;
			for(int neighbor:graph[node]) if(!removed[neighbor] && --degrees[neighbor]==1) pending.addLast(neighbor);
		}
		return result;
	}

	private long[] eliminate(Set<Integer>[] source) {
		@SuppressWarnings("unchecked") Set<Integer>[] graph=new Set[source.length];
		for(int i=0;i<source.length;i++) graph[i]=new LinkedHashSet<>(source[i]);
		boolean[] removed=new boolean[source.length]; int maxSeparator=0; long maxRows=1;
		for(int step=0;step<graph.length;step++) {
			int selected=-1; long bestFill=Long.MAX_VALUE;
			for(int node=0;node<graph.length;node++) if(!removed[node]) {
				long fill=missingEdges(graph[node],graph,removed);
				if(fill<bestFill || fill==bestFill && (selected<0 || graph[node].size()<graph[selected].size())
					|| fill==bestFill && selected>=0 && graph[node].size()==graph[selected].size() && node<selected) {
					selected=node; bestFill=fill;
				}
			}
			List<Integer> neighbors=graph[selected].stream().filter(node -> !removed[node]).toList();
			maxSeparator=Math.max(maxSeparator,neighbors.size());
			long rows=originalDomains[selected];
			for(int neighbor:neighbors) rows=saturatedMultiply(rows,originalDomains[neighbor]);
			maxRows=Math.max(maxRows,rows);
			for(int i=0;i<neighbors.size();i++) for(int j=i+1;j<neighbors.size();j++) {
				graph[neighbors.get(i)].add(neighbors.get(j)); graph[neighbors.get(j)].add(neighbors.get(i));
			}
			removed[selected]=true;
			for(int neighbor:neighbors) graph[neighbor].remove(selected);
		}
		return new long[] {maxSeparator,maxRows};
	}

	private static long missingEdges(Set<Integer> neighbors,Set<Integer>[] graph,boolean[] removed) {
		List<Integer> active=neighbors.stream().filter(node -> !removed[node]).toList(); long missing=0;
		for(int i=0;i<active.size();i++) for(int j=i+1;j<active.size();j++)
			if(!graph[active.get(i)].contains(active.get(j))) missing++;
		return missing;
	}

	private static long saturatedMultiply(long left,long right) {
		return right!=0 && left>ROW_LIMIT/right ? ROW_LIMIT : left*right;
	}
	private static long saturatedAdd(long left,long right) {
		return left>Long.MAX_VALUE-right ? Long.MAX_VALUE : left+right;
	}

	public synchronized void emitStructure() {
		if(structureEmitted) return;
		structureEmitted=true;
		int maximum=positiveInt(MAX_FACTORS_PROPERTY,DEFAULT_MAX_FACTORS);
		int emitted=Math.min(maximum,factors.size());
		System.err.println("[MemoFactorMeta] {\"schema\":1,\"nodes\":"+originalDomains.length
			+",\"factors\":"+factors.size()+",\"emittedFactors\":"+emitted
			+",\"omittedFactors\":"+(factors.size()-emitted)+",\"domains\":"+array(originalDomains)+"}");
		for(int i=0;i<emitted;i++)
			System.err.println("[MemoFactor] "+factorJson(factors.get(i).snapshot(originalDomains)));
		for(GraphSummary summary:graphSummaries()) System.err.println("[MemoFactorGraph] "+graphJson(summary));
	}

	public void emitTimings() {
		for(CategoryTiming timing:timings())
			System.err.println("[MemoFactorEval] {\"schema\":1,\"category\":\""+timing.category()
				+"\",\"factors\":"+timing.factors()+",\"instrumentedFactors\":"+timing.instrumentedFactors()
				+",\"evaluations\":"+timing.evaluations()+",\"nanos\":"+timing.evaluationNanos()
				+",\"cumulative\":true}");
	}

	private static String factorJson(Factor factor) {
		return "{\"schema\":1,\"id\":"+factor.id()+",\"kind\":\""+factor.kind()
			+"\",\"category\":\""+factor.category()+"\",\"site\":"+factor.site()
			+",\"scope\":"+array(factor.scope())+",\"domains\":"+array(factor.domains())
			+",\"instrumented\":"+factor.evaluatorInstrumented()+",\"tags\":["
			+factor.tags().stream().map(tag -> "\""+escape(tag)+"\"").reduce((a,b)->a+","+b).orElse("")+ "]}";
	}

	private static String graphJson(GraphSummary summary) {
		return "{\"schema\":1,\"excludedCategory\":\""+summary.excludedCategory()
			+"\",\"nodes\":"+summary.nodes()+",\"factors\":"+summary.factors()+",\"edges\":"+summary.edges()
			+",\"components\":"+summary.components()+",\"leafPeeled\":"+summary.leafPeeled()
			+",\"coreNodes\":"+summary.coreNodes()+",\"estimatedMaxSeparatorVariables\":"
			+summary.estimatedMaxSeparatorVariables()+",\"estimatedMaxTableRows\":"+summary.estimatedMaxTableRows()
			+",\"graphSkipped\":"+summary.graphSkipped()+",\"eliminationSkipped\":"+summary.eliminationSkipped()
			+",\"estimateKind\":\"separately-optimized-deterministic-min-fill-not-treewidth\"}";
	}

	private static int positiveInt(String property,int fallback) {
		String value=System.getProperty(property);
		if(value==null) return fallback;
		try { int parsed=Integer.parseInt(value); return parsed>=0 ? parsed : fallback; }
		catch(NumberFormatException ignored) { return fallback; }
	}

	private static String array(int[] values) {
		return "["+Arrays.stream(values).mapToObj(Integer::toString).reduce((a,b)->a+","+b).orElse("")+"]";
	}

	private static String escape(String value) {
		return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
	}
}

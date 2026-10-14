/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedEnvironment;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedOrigin;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.VisitStatus;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.Occurrence;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.OccurrenceContext;
import org.apache.sysds.runtime.instructions.fed.FederatedRecodeMetadataCollector;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;

/** Request-local, source-identity-verified transformencode preparation facts. */
final class MemoTransformFacts {
	static final String PREPARE_PROPERTY="sysds.fedplanner.memo.prepareTransformMetadata";

	@FunctionalInterface interface Collector {
		Collected collect(DataOp originalSource,FederatedSourceMetadata acquiredMetadata,String rawSpec);
	}
	record Collected(long encodedColumns,long metadataRows,long metadataColumns) {
		Collected {
			if(encodedColumns<=0 || metadataRows<=0 || metadataColumns<=0)
				throw new IllegalArgumentException("Collected transform metadata dimensions must be positive");
		}
	}
	static final class Proof {
		private final int functionOwner,sourceOwner,outputArity;
		private final String rawSpec;
		private final DataType inputDataType;
		private final DataType primaryDataType,metadataDataType;
		private final long inputRows,inputColumns,outputRows,outputColumns,metadataRows,metadataColumns;
		private Proof(int functionOwner,int sourceOwner,String rawSpec,DataType inputDataType,
			DataType primaryDataType,DataType metadataDataType,
			long inputRows,long inputColumns,long outputRows,long outputColumns,
			long metadataRows,long metadataColumns,int outputArity) {
			this.functionOwner=functionOwner; this.sourceOwner=sourceOwner;
			this.rawSpec=Objects.requireNonNull(rawSpec); this.inputDataType=Objects.requireNonNull(inputDataType);
			this.primaryDataType=Objects.requireNonNull(primaryDataType);
			this.metadataDataType=Objects.requireNonNull(metadataDataType);
			this.inputRows=inputRows; this.inputColumns=inputColumns;
			this.outputRows=outputRows; this.outputColumns=outputColumns;
			this.metadataRows=metadataRows; this.metadataColumns=metadataColumns;
			this.outputArity=outputArity;
		}
		int functionOwner() { return functionOwner; }
		int sourceOwner() { return sourceOwner; }
		String rawSpec() { return rawSpec; }
		DataType inputDataType() { return inputDataType; }
		DataType primaryDataType() { return primaryDataType; }
		DataType metadataDataType() { return metadataDataType; }
		long inputRows() { return inputRows; }
		long inputColumns() { return inputColumns; }
		long outputRows() { return outputRows; }
		long outputColumns() { return outputColumns; }
		long metadataRows() { return metadataRows; }
		long metadataColumns() { return metadataColumns; }
		int outputArity() { return outputArity; }
	}

	private record CollectionKey(int sourceOwner,String rawSpec) { }
	private record Candidate(int functionOwner,int sourceOwner,String rawSpec,FunctionOp function,
		DataOp source,FederatedSourceMetadata metadata,MemoSources.Source sourceShape) { }
	private record OriginVisit(int occurrence,List<Integer> frame) {
		private OriginVisit { frame=List.copyOf(frame); }
	}
	private final MemoProgramFacts facts;
	private final MemoSources sources;
	private final Map<Integer,Proof> proofs;
	private final List<String> diagnostics;

	private MemoTransformFacts(MemoProgramFacts facts,MemoSources sources,Map<Integer,Proof> proofs,
		List<String> diagnostics) {
		this.facts=Objects.requireNonNull(facts); this.sources=Objects.requireNonNull(sources);
		this.proofs=Map.copyOf(proofs);
		this.diagnostics=List.copyOf(diagnostics);
	}

	static MemoTransformFacts none(MemoProgramFacts facts,MemoSources sources) {
		return new MemoTransformFacts(facts,sources,Map.of(),List.of());
	}

	static MemoTransformFacts prepareDefault(MemoProgramFacts facts,MemoSources sources) {
		return prepare(facts,sources,(source,metadata,spec) -> {
			var result=FederatedRecodeMetadataCollector.collect(metadata,spec);
			var prepared=result.metadata();
			return new Collected(prepared.encoder().getNumOutCols(),prepared.metadata().getNumRows(),
				prepared.metadata().getNumColumns());
		},FederatedPlannerUtils::resolveFederatedSourceMetadata);
	}

	static MemoTransformFacts prepare(MemoProgramFacts facts,MemoSources sources,Collector collector) {
		return prepare(facts,sources,collector,null);
	}

	private static MemoTransformFacts prepare(MemoProgramFacts facts,MemoSources sources,Collector collector,
		Function<DataOp,FederatedSourceMetadata> refresh) {
		Objects.requireNonNull(collector,"transform metadata collector");
		if(!Boolean.getBoolean(PREPARE_PROPERTY)) return none(facts,sources);
		long started=System.nanoTime();
		Map<Integer,Proof> proofs=new LinkedHashMap<>();
		Map<CollectionKey,Collected> collected=new LinkedHashMap<>();
		Map<Integer,FederatedSourceMetadata> refreshed=new LinkedHashMap<>();
		List<String> diagnostics=new ArrayList<>();
		List<Candidate> candidates=new ArrayList<>();
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		MemoReachableContexts reachable=new MemoReachableContexts(facts,resolver);
		int requests=0;
		boolean failed=false;
		for(int owner=0;owner<facts.compiledOwnerCount();owner++) {
			Hop hop=facts.compiledOwnerHop(owner);
			if(!(hop instanceof FunctionOp function) || !transformEncode(function)
				|| function.getOutputs()==null || function.getOutputs().size()!=2
				|| function.getInput().size()!=2 || !(function.getInput(1) instanceof LiteralOp literal)) continue;
			String rawSpec=literal.getStringValue();
			if(!TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata(rawSpec)) continue;
			Integer sourceOwner=verifiedSourceOwner(owner,facts,sources,resolver,reachable);
			if(sourceOwner==null) {
				failed=true; diagnostics.add("owner="+owner+" source binding is not uniquely proved"); continue;
			}
			MemoSources.Source source=sources.source(sourceOwner);
			FederatedSourceMetadata metadata=sources.sourceMetadata(sourceOwner);
			Hop original=facts.compiledOwnerHop(sourceOwner);
			if(source==null || metadata==null || !(original instanceof DataOp data)
				|| data.getOp()!=OpOpData.FEDERATED) {
				failed=true; diagnostics.add("owner="+owner+" source identity is incomplete"); continue;
			}
			candidates.add(new Candidate(owner,sourceOwner,rawSpec,function,data,metadata,source));
		}
		// Resolve and compare current source descriptors only after every eligible
		// owner has passed the program-local binding proof. A failed proof therefore
		// cannot trigger even a partial metadata-preparation acquisition.
		if(!failed && refresh!=null) for(Candidate candidate:candidates) {
			int owner=candidate.functionOwner(),sourceOwner=candidate.sourceOwner();
			if(refreshed.containsKey(sourceOwner)) continue;
			try {
				FederatedSourceMetadata current=refresh.apply(candidate.source());
				if(!sources.matchesSourceMetadata(sourceOwner,current)) {
					failed=true; diagnostics.add("owner="+owner+" source metadata changed after acquisition"); continue;
				}
				refreshed.put(sourceOwner,current);
			}
			catch(RuntimeException ex) {
				failed=true; diagnostics.add("owner="+owner+" source metadata refresh failed: "+describe(ex));
			}
		}
		if(!failed) for(Candidate candidate:candidates) {
			if(failed) break;
			int owner=candidate.functionOwner(),sourceOwner=candidate.sourceOwner();
			String rawSpec=candidate.rawSpec(); FunctionOp function=candidate.function();
			CollectionKey key=new CollectionKey(sourceOwner,rawSpec);
			Collected result=collected.get(key);
			if(result==null) try {
				requests++;
				FederatedSourceMetadata metadata=refresh==null ? candidate.metadata()
					: refreshed.get(sourceOwner);
				result=collector.collect(candidate.source(),metadata,rawSpec);
				if(result==null) {
					failed=true; diagnostics.add("owner="+owner+" collector returned no result"); continue;
				}
				collected.put(key,result);
			}
			catch(RuntimeException ex) {
				failed=true; diagnostics.add("owner="+owner+" collector failed: "+describe(ex)); continue;
			}
			proofs.put(owner,new Proof(owner,sourceOwner,rawSpec,function.getInput(0).getDataType(),
				function.getOutputs().get(0).getDataType(),function.getOutputs().get(1).getDataType(),
				candidate.sourceShape().rows(),candidate.sourceShape().columns(),candidate.sourceShape().rows(),result.encodedColumns(),
				result.metadataRows(),result.metadataColumns(),function.getOutputs().size()));
		}
		if(failed) proofs.clear();
		long elapsed=System.nanoTime()-started;
		System.err.println("TransformMetadataPreparationReceipt schema=transform-metadata-preparation-v1"
			+" metadataPreparationNanos="+elapsed+" collections="+requests+" owners="+proofs.size()
			+" status="+(failed ? "FAILED" : "SUCCESS"));
		if(failed) for(String diagnostic:diagnostics)
			System.err.println("MemoTransformMetadataFailure "+diagnostic);
		return new MemoTransformFacts(facts,sources,proofs,diagnostics);
	}

	private static Integer verifiedSourceOwner(int owner,MemoProgramFacts facts,MemoSources sources,
		MemoBindingResolver resolver,MemoReachableContexts reachable) {
		List<Occurrence> occurrences=facts.occurrences().stream()
			.filter(value -> value.compiledOwnerId()==owner).toList();
		if(occurrences.isEmpty()) return null;
		Integer expected=null;
		for(Occurrence occurrence:occurrences) {
			List<OccurrenceContext> contexts=facts.occurrenceContexts(occurrence.id());
			if(contexts.isEmpty() || occurrence.hop().getInput().isEmpty()) return null;
			int input=facts.inputBinding(occurrence.id(),0);
			if(input<0) return null;
			for(OccurrenceContext occurrenceContext:contexts) {
				EvaluationContext base=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,
					4096,occurrenceContext.callFrame(),Map.of());
				List<Integer> witness=new ArrayList<>();
				boolean[] invalid={false};
				var visit=reachable.visitBindings(occurrence,base,List.of(input),environment -> {
					if(environment.origins().size()!=1) { invalid[0]=true; return true; }
					Integer source=directSource(environment.origins().get(0),environment,base,facts,sources,resolver,
						new LinkedHashSet<>());
					if(source==null) { invalid[0]=true; return true; }
					witness.add(source);
					return true;
				});
				if(visit.status()!=VisitStatus.COMPLETE || visit.environmentCount()<1 || invalid[0]
					|| witness.size()!=visit.environmentCount())
					return null;
				for(Integer source:witness) {
					if(expected==null) expected=source;
					else if(!expected.equals(source)) return null;
				}
			}
		}
		return expected;
	}

	private static Integer directSource(ResolvedOrigin origin,ResolvedEnvironment environment,
		EvaluationContext parent,MemoProgramFacts facts,MemoSources sources,MemoBindingResolver resolver,
		Set<OriginVisit> active) {
		if(origin.kind()!=OriginKind.COMPILED_OWNER || origin.referenceId()<0
			|| origin.referenceId()>=facts.occurrences().size()) return null;
		Occurrence occurrence=facts.occurrence(origin.referenceId());
		int owner=occurrence.compiledOwnerId();
		if(sources.source(owner)!=null && occurrence.hop() instanceof DataOp data
			&& data.getOp()==OpOpData.FEDERATED) return owner;
		Hop hop=occurrence.hop();
		boolean frameCast=hop instanceof UnaryOp unary && unary.getOp()==OpOp1.CAST_AS_FRAME
			&& unary.getInput().size()==1 && unary.getInput(0).getDataType()==DataType.MATRIX;
		if(!frameCast || hop.getInput().isEmpty()) return null;
		OriginVisit key=new OriginVisit(occurrence.id(),origin.callFrame());
		if(!active.add(key)) return null;
		try {
			int input=facts.inputBinding(occurrence.id(),0);
			if(input<0) return null;
			EvaluationContext exact=new EvaluationContext(environment.guards(),environment.loops(),
				parent.selections(),parent.maxDepth(),origin.callFrame(),environment.iterationPhases(),
				environment.cardinalities());
			List<Integer> witness=new ArrayList<>();
			boolean[] invalid={false};
			var visit=resolver.visitJoint(List.of(input),exact,nested -> {
				if(nested.origins().size()!=1) { invalid[0]=true; return true; }
				Integer source=directSource(nested.origins().get(0),nested,exact,facts,sources,resolver,active);
				if(source==null) { invalid[0]=true; return true; }
				witness.add(source); return true;
			});
			if(visit.status()!=VisitStatus.COMPLETE || visit.environmentCount()<1 || invalid[0]
				|| witness.size()!=visit.environmentCount()) return null;
			Integer source=witness.get(0);
			for(Integer value:witness) if(!source.equals(value)) return null;
			return source;
		}
		finally { active.remove(key); }
	}

	private static boolean transformEncode(FunctionOp function) {
		return function.getFunctionType()==FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			&& function.getFunctionName().equalsIgnoreCase(Opcodes.TRANSFORMENCODE.toString());
	}

	private static String describe(RuntimeException ex) {
		return ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage());
	}

	Proof proof(int owner) { return proofs.get(owner); }
	List<String> diagnostics() { return diagnostics; }
	void requireRequest(MemoProgramFacts facts,MemoSources sources) {
		if(this.facts!=facts || this.sources!=sources)
			throw new IllegalArgumentException("Transform metadata authority belongs to another planning request");
	}
}

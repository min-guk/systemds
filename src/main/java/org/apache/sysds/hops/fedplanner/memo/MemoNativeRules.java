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
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

/**
 * Source-free adapter from the existing runtime oracle to memo native rules.
 *
 * <p>Only abstract input layouts are enumerated. Source identities, reaching
 * definitions, uploads and collections are deliberately outside this class.
 * A result with incomplete coverage is useful for shadow comparison, but must
 * not be used to prove global optimality.</p>
 */
public final class MemoNativeRules {
	public enum InputRole { PAYLOAD, SCALAR, METADATA, CONTROL }
	public enum DiagnosticKind { COVERAGE_GAP, POLICY_EXCLUSION }

	public record Diagnostic(DiagnosticKind kind, String code, String pattern, String message) {
		public Diagnostic {
			Objects.requireNonNull(kind);
			Objects.requireNonNull(code);
			Objects.requireNonNull(pattern);
			Objects.requireNonNull(message);
		}
	}

	public record Result(List<RuleMemo.Rule> rules, List<InputRole> inputRoles,
		BitSet metadataInputs, boolean complete, List<Diagnostic> diagnostics,
		GenerationStats generation) {
		public Result(List<RuleMemo.Rule> rules,List<InputRole> inputRoles,BitSet metadataInputs,
			boolean complete,List<Diagnostic> diagnostics) {
			this(rules,inputRoles,metadataInputs,complete,diagnostics,
				new GenerationStats(0,0,0,0,0));
		}
		public Result {
			rules = List.copyOf(rules);
			inputRoles = List.copyOf(inputRoles);
			metadataInputs = (BitSet) metadataInputs.clone();
			diagnostics = List.copyOf(diagnostics);
			Objects.requireNonNull(generation);
		}

		@Override public BitSet metadataInputs() {
			return (BitSet) metadataInputs.clone();
		}

		public List<Diagnostic> coverageGaps() {
			return diagnostics.stream().filter(d -> d.kind() == DiagnosticKind.COVERAGE_GAP).toList();
		}

		public List<Diagnostic> policyExclusions() {
			return diagnostics.stream().filter(d -> d.kind() == DiagnosticKind.POLICY_EXCLUSION).toList();
		}
	}
	public record GenerationStats(long cpPatterns, long prefixRequests, long prunedPrefixes,
		long unknownPrefixes, long visitedNativePatterns, long determinantRegions,
		long oracleEvaluations, long evidenceReuses, long publishedRules,
		long coverageObligations) {
		public GenerationStats(long cpPatterns,long prefixRequests,long prunedPrefixes,
			long unknownPrefixes,long visitedNativePatterns) {
			this(cpPatterns,prefixRequests,prunedPrefixes,unknownPrefixes,visitedNativePatterns,
				0,0,0,0,0);
		}
	}
	private static final class GenerationCounter {
		private long cpPatterns, prefixRequests, prunedPrefixes, unknownPrefixes, visitedNativePatterns;
		private long determinantRegions, oracleEvaluations, evidenceReuses, publishedRules;
		private long coverageObligations;
		private GenerationStats snapshot() {
			return new GenerationStats(cpPatterns,prefixRequests,prunedPrefixes,unknownPrefixes,
				visitedNativePatterns,determinantRegions,oracleEvaluations,evidenceReuses,
				publishedRules,coverageObligations);
		}
		private void add(OracleFacade.SupportedPatternDiagnostics value) {
			prefixRequests += value.prefixRequests();
			prunedPrefixes += value.prunedPrefixes();
			unknownPrefixes += value.unknownPrefixes();
			visitedNativePatterns += value.visitedLeaves();
			determinantRegions += value.determinantRegions();
			oracleEvaluations += value.oracleEvaluations();
			evidenceReuses += value.evidenceReuses();
			coverageObligations += value.coverageObligations();
		}
		private void add(GenerationStats value) {
			cpPatterns += value.cpPatterns();
			prefixRequests += value.prefixRequests();
			prunedPrefixes += value.prunedPrefixes();
			unknownPrefixes += value.unknownPrefixes();
			visitedNativePatterns += value.visitedNativePatterns();
			determinantRegions += value.determinantRegions();
			oracleEvaluations += value.oracleEvaluations();
			evidenceReuses += value.evidenceReuses();
			publishedRules += value.publishedRules();
			coverageObligations += value.coverageObligations();
		}
		private void published() { publishedRules++; }
	}

	public record CacheStats(long hits, long misses, int entries) { }

	// The only native request identity. Callers obtain it through RequestFactory;
	// they must not reconstruct operator selectors or substitute a shape-only key.
	record RequestKey(String operator, int workers, ShapeHint.DiagnosticSnapshot shape) { }
	static final class Request {
		private final Hop hop;
		private final int workers;
		private final ShapeHint.DiagnosticSnapshot shape;
		private final RequestKey key;
		private Request(Hop hop,int workers,ShapeHint.DiagnosticSnapshot shape,RequestKey key) {
			this.hop=hop; this.workers=workers; this.shape=shape; this.key=key;
		}
		RequestKey key() { return key; }
	}
	record PreparationStats(long preparedRequests,long canonicalStringsCreated,
		long canonicalStringsReused,long uncacheableRequests) { }
	/** Build-local, thread-confined preparation. Only immutable strings enter the interner. */
	static final class RequestFactory {
		private final Map<CharSequence,String> operators = new TreeMap<>(CharSequence::compare);
		private final StringBuilder buffer = new StringBuilder(128);
		private long prepared,created,reused,uncacheable;
		Request prepare(Hop hop,int workers,ShapeHint hint) {
			Request request=prepareRequest(hop,workers,hint,this);
			prepared++;
			if(request.key()==null) uncacheable++;
			return request;
		}
		private String operator(Hop hop) {
			buffer.setLength(0);
			appendOperatorKey(hop,buffer);
			String cached=operators.get(buffer);
			if(cached!=null) { reused++; return cached; }
			String key=buffer.toString();
			operators.put(key,key); created++;
			return key;
		}
		PreparationStats statistics() { return new PreparationStats(prepared,created,reused,uncacheable); }
	}

	private final OracleFacade oracle;
	private final Map<RequestKey, Result> cache = new LinkedHashMap<>();
	private long cacheHits;
	private long cacheMisses;
	private final GenerationCounter generation = new GenerationCounter();

	public MemoNativeRules() {
		this(new OracleFacade(RulesCore.RulesModule.createDefaultRegistry()));
	}

	MemoNativeRules(OracleFacade oracle) {
		this.oracle = Objects.requireNonNull(oracle);
	}

	/**
	 * Enumerates native rules for one immutable HOP signature and explicit shape
	 * authority. The supplied hint may contain UNKNOWN values, which are retained
	 * as a coverage obligation rather than guessed from a source realization.
	 */
	public synchronized Result rules(Hop hop, int workers, ShapeHint explicitShape) {
		return rules(prepareRequest(hop,workers,explicitShape,null));
	}

	private static Request prepareRequest(Hop hop,int workers,ShapeHint explicitShape,RequestFactory factory) {
		Objects.requireNonNull(hop, "hop");
		Objects.requireNonNull(explicitShape, "explicit shape hint");
		if(workers < 1)
			throw new IllegalArgumentException("A worker pool must be nonempty");
		ShapeHint.DiagnosticSnapshot shape = explicitShape.diagnosticSnapshot();
		RequestKey key=cacheable(hop) ? new RequestKey(factory==null ? operatorKey(hop)
			: factory.operator(hop),workers,shape) : null;
		return new Request(hop,workers,shape,key);
	}

	/** Evaluate immediately against the unmodified private view used to prepare this request. */
	synchronized Result rules(Request request) {
		Objects.requireNonNull(request,"request");
		if(request.key==null) {
			cacheMisses++;
			Result result=enumerate(request.hop,request.workers,request.shape);
			generation.add(result.generation());
			return result;
		}
		Result cached = cache.get(request.key);
		if(cached != null) {
			cacheHits++;
			return cached;
		}
		cacheMisses++;
		Result result = enumerate(request.hop,request.workers,request.shape);
		generation.add(result.generation());
		cache.put(request.key, result);
		return result;
	}

	public synchronized CacheStats cacheStats() {
		return new CacheStats(cacheHits, cacheMisses, cache.size());
	}
	public synchronized GenerationStats generationStats() { return generation.snapshot(); }

	private Result enumerate(Hop hop, int workers, ShapeHint.DiagnosticSnapshot shape) {
		GenerationCounter generation = new GenerationCounter();
		List<InputRole> roles = inputRoles(hop);
		BitSet metadata = new BitSet(roles.size());
		for(int i = 0; i < roles.size(); i++)
			if(roles.get(i) == InputRole.METADATA)
				metadata.set(i);
		List<Diagnostic> diagnostics = new ArrayList<>();
		LinkedHashSet<RuleMemo.Rule> rules = new LinkedHashSet<>();
		if(hop instanceof FunctionOp function
			&& function.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN) {
			int named = function.getOutputVariableNames() == null ? 0 : function.getOutputVariableNames().length;
			int structural = function.getOutputs() == null ? 0 : function.getOutputs().size();
			if(named == 0 || structural == 0 || named != structural) {
				diagnostics.add(gap("MULTIRETURN_OUTPUT_CONTRACT_INCOMPLETE", "[]",
					"The CP builtin requires matching named and structural output slots"));
				return new Result(List.of(), roles, metadata, false, diagnostics,generation.snapshot());
			}
			String opcode = function.getFunctionName().toLowerCase(java.util.Locale.ROOT);
			if(!cpMultiReturnBuiltin(opcode)) {
				diagnostics.add(gap("MULTIRETURN_CP_RUNTIME_UNSUPPORTED", "[]",
					"No verified CP multi-return instruction for " + opcode));
				return new Result(List.of(), roles, metadata, false, diagnostics,generation.snapshot());
			}
			List<MemoPartition> inputs = roles.stream().map(ignored -> MemoPartition.LOCAL).toList();
			List<MemoPartition> outputs = java.util.Collections.nCopies(structural, MemoPartition.LOCAL);
			addRule(rules,new RuleMemo.Rule(ExecType.CP,inputs,outputs,
				"cp/multireturn/"+opcode),generation);
			if(opcode.equals(Opcodes.TRANSFORMENCODE.toString()))
				enumerateTransformEncode(function,workers,shape,roles,prepared(function),rules,diagnostics,generation);
			return new Result(new ArrayList<>(rules), roles, metadata,
				diagnostics.stream().noneMatch(d -> d.kind() == DiagnosticKind.COVERAGE_GAP), diagnostics,
				generation.snapshot());
		}

		if(isBoundaryOrSource(hop)) {
			diagnostics.add(gap("BOUNDARY_RULE_OWNED_BY_PROGRAM_FACTS", "[]",
				"Sources, transient/function carriers and their exact bindings are not ordinary native operators"));
			return new Result(List.of(), roles, metadata, false, diagnostics,generation.snapshot());
		}

		// CP payload kernels require LOCAL data. Metadata positions retain every
		// abstract source layout because reading a FederationMap is not a COLLECT.
		visitPatterns(patternAxes(roles,workers,true),cpPattern -> {
			generation.cpPatterns++;
			addRule(rules,new RuleMemo.Rule(ExecType.CP,cpPattern,List.of(MemoPartition.LOCAL),"cp/local"),
				generation);
		});

		OracleFacade.PreparedDecision prepared = oracle.prepareDecision(hop);
		OracleFacade.SupportedPatternDiagnostics generated=visitSupportedPatterns(prepared,roles,workers,shape,
			(pattern,evidence,ex) -> {
			if(ex!=null) {
				diagnostics.add(gap("ORACLE_EXCEPTION", pattern.toString(),
					ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage())));
				return;
			}
			OpCaps caps = evidence.caps();
			if(caps.reason() == ReasonCode.RULE_ERROR) {
				diagnostics.add(gap("ORACLE_RULE_ERROR", pattern.toString(),
					caps.detail().orElse("oracle returned RULE_ERROR")));
				return;
			}
			if(caps.reason() == ReasonCode.NOT_IMPLEMENTED) {
				diagnostics.add(gap("NATIVE_CATALOG_NOT_IMPLEMENTED", pattern.toString(),
					caps.detail().orElse("the selected oracle rule has no capability implementation")));
				return;
			}
			if(!evidence.shapeProof().missingRequiredFacts().isEmpty()) {
				// An all-local pattern is already represented by the unconditional CP
				// baseline and cannot become a FED kernel through additional shape facts.
				// Missing matrix dimensions are therefore not a native-coverage gap here.
				if(caps.exec() != ExecType.FED && pattern.stream()
					.allMatch(value -> value.normalize(workers) == MemoPartition.LOCAL))
					return;
				diagnostics.add(gap("MISSING_SHAPE_FACT", pattern.toString(),
					evidence.shapeProof().missingRequiredFacts().toString()));
				return;
			}
			if(caps.exec() != ExecType.FED)
				return;

			Optional<MemoPartition> vectorLineage = aggregateBinaryVectorLocalType(hop, pattern, workers);
			String variant = caps.placement() == FederatedOutput.LOUT && vectorLineage.isPresent()
				? "fed/vector-local/" + vectorLineage.get().name().toLowerCase()
				: "oracle/" + caps.opcode() + '/' + caps.reason().name().toLowerCase();
			Optional<MemoPartition> output = nativeOutput(hop, pattern, workers, shape, caps, diagnostics);
			output.ifPresent(value -> addRule(rules,
				new RuleMemo.Rule(ExecType.FED,pattern,List.of(value),variant),generation));

			if(caps.placement() == FederatedOutput.FOUT
				&& OracleFacade.supportsForcedLocalFederatedOutput(hop)) {
				String localVariant = vectorLineage
					.map(value -> "fed/vector-local/" + value.name().toLowerCase())
					.orElse("fed/forced-local");
				addRule(rules,new RuleMemo.Rule(ExecType.FED,pattern,List.of(MemoPartition.LOCAL),localVariant),
					generation);
			}
		});
		generation.add(generated);
		return new Result(new ArrayList<>(rules), roles, metadata,
			diagnostics.stream().noneMatch(d -> d.kind() == DiagnosticKind.COVERAGE_GAP), diagnostics,
			generation.snapshot());
	}

	private OracleFacade.PreparedDecision prepared(Hop hop) {
		return oracle.prepareDecision(hop);
	}

	private static boolean cpMultiReturnBuiltin(String opcode) {
		return opcode.equals(Opcodes.QR.toString()) || opcode.equals(Opcodes.LU.toString())
			|| opcode.equals(Opcodes.EIGEN.toString()) || opcode.equals(Opcodes.FFT.toString())
			|| opcode.equals(Opcodes.FFT_LINEARIZED.toString()) || opcode.equals(Opcodes.STFT.toString())
			|| opcode.equals(Opcodes.SVD.toString()) || opcode.equals(Opcodes.TRANSFORMENCODE.toString());
	}

	private static void enumerateTransformEncode(FunctionOp hop,int workers,
		ShapeHint.DiagnosticSnapshot shape,List<InputRole> roles,OracleFacade.PreparedDecision prepared,
		LinkedHashSet<RuleMemo.Rule> rules,List<Diagnostic> diagnostics,GenerationCounter generation) {
		OracleFacade.SupportedPatternDiagnostics generated=visitSupportedPatterns(prepared,roles,workers,shape,
			(pattern,evidence,failure) -> {
			if(pattern.isEmpty() || pattern.get(0) == MemoPartition.LOCAL) return;
			if(failure!=null) {
				diagnostics.add(gap("ORACLE_EXCEPTION",pattern.toString(),
					failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage())));
				return;
			}
			OpCaps caps=evidence.caps();
			// Determinant-region compression may surface one unresolved obligation
			// instead of every concrete CP leaf. Preserve it before filtering non-FED
			// transformencode variants so multi-output coverage cannot become complete.
			if(caps.reason()==ReasonCode.RULE_ERROR) {
				diagnostics.add(gap("ORACLE_RULE_ERROR",pattern.toString(),
					caps.detail().orElse("oracle returned RULE_ERROR")));
				return;
			}
			if(caps.reason()==ReasonCode.NOT_IMPLEMENTED) {
				diagnostics.add(gap("NATIVE_CATALOG_NOT_IMPLEMENTED",pattern.toString(),
					caps.detail().orElse("the selected oracle rule has no capability implementation")));
				return;
			}
			if(!evidence.shapeProof().missingRequiredFacts().isEmpty()) {
				diagnostics.add(gap("MISSING_SHAPE_FACT",pattern.toString(),
					evidence.shapeProof().missingRequiredFacts().toString()));
				return;
			}
			if(caps.exec()!=ExecType.FED) return;
			MemoPartition primary=pattern.get(0).normalize(workers);
			if(primary==MemoPartition.COL) {
				diagnostics.add(exclusion("TRANSFORMENCODE_NONCANONICAL_COL_OUTPUT",pattern.toString(),
					"Encoding can change the partitioned column axis"));
				return;
			}
			if(primary==MemoPartition.ROW && !transformEncodeRowCanonical(hop)) {
				diagnostics.add(gap("TRANSFORMENCODE_ROW_CANONICALITY_UNRESOLVED",pattern.toString(),
					"A literal recode/dummycode-only specification is required by the verified runtime proof"));
				return;
			}
			if(primary!=MemoPartition.ROW && !(workers==1 && primary==MemoPartition.REP)) return;
			addRule(rules,new RuleMemo.Rule(ExecType.FED,pattern,List.of(primary,MemoPartition.LOCAL),
				"oracle/transformencode/ok"),generation);
		});
		generation.add(generated);
	}

	private static void addRule(LinkedHashSet<RuleMemo.Rule> rules,RuleMemo.Rule rule,
		GenerationCounter generation) {
		if(rules.add(rule)) generation.published();
	}

	private static boolean transformEncodeRowCanonical(FunctionOp hop) {
		if(hop.getInput().size()!=2 || !(hop.getInput(1) instanceof LiteralOp literal)) return false;
		try {
			JSONObject spec=new JSONObject(literal.getStringValue());
			for(Object key : spec.keySet())
				if(!Set.of("ids","recode","dummycode","cofeePublicRecodeMetadata").contains(key)) return false;
			return spec.containsKey("recode") || spec.containsKey("dummycode");
		}
		catch(JSONException ex) { return false; }
	}

	private static Optional<MemoPartition> nativeOutput(Hop hop, List<MemoPartition> pattern,
		int workers, ShapeHint.DiagnosticSnapshot shape, OpCaps caps, List<Diagnostic> diagnostics) {
		if(caps.placement() == FederatedOutput.LOUT)
			return Optional.of(MemoPartition.LOCAL);
		FType outputType = caps.foutFType().orElse(null);
		if(outputType == null) {
			diagnostics.add(gap("MISSING_NATIVE_OUTPUT_LAYOUT", pattern.toString(),
				"FED/FOUT did not identify an output layout"));
			return Optional.empty();
		}
		if(outputType == FType.PART || outputType == FType.OTHER) {
			diagnostics.add(exclusion("NONCANONICAL_PERSISTENT_NATIVE_OUTPUT", pattern.toString(),
				"Runtime native output " + outputType
					+ " is not a persistent LOCAL/ROW/COL/REP memo layout"));
			return Optional.empty();
		}
		if(hop instanceof IndexingOp indexing && hop.getDataType() == DataType.MATRIX) {
			CanonicalEffect effect = indexingCanonicalEffect(indexing, outputType, workers, shape);
			if(effect != CanonicalEffect.PROVED) {
				diagnostics.add(effect == CanonicalEffect.UNKNOWN
					? gap("INDEXING_CANONICAL_OUTPUT_UNRESOLVED", pattern.toString(),
						"Literal bounds, input shape and canonical worker ranges are required")
					: exclusion("INDEXING_NONCANONICAL_NATIVE_OUTPUT", pattern.toString(),
						"The filtered runtime ranges do not equal the canonical output ranges on all workers"));
				return Optional.empty();
			}
		}
		if(hop instanceof BinaryOp binary
			&& (binary.getOp() == OpOp2.CBIND || binary.getOp() == OpOp2.RBIND)) {
			CanonicalEffect effect = appendCanonicalEffect(binary, outputType, workers, shape);
			if(effect != CanonicalEffect.PROVED) {
				diagnostics.add(effect == CanonicalEffect.UNKNOWN
					? gap("APPEND_CANONICAL_OUTPUT_UNRESOLVED", pattern.toString(),
						"Exact input and output shapes are required for canonical append output")
					: exclusion("APPEND_NONCANONICAL_NATIVE_OUTPUT", pattern.toString(),
						"Appending along the partitioned axis does not preserve the fixed canonical map"));
				return Optional.empty();
			}
		}
		if(!(hop instanceof IndexingOp)
			&& !(hop instanceof BinaryOp binary
				&& (binary.getOp() == OpOp2.CBIND || binary.getOp() == OpOp2.RBIND))) {
			CanonicalEffect effect = ordinaryCanonicalEffect(hop, pattern, outputType, workers, shape);
			if(effect != CanonicalEffect.PROVED) {
				diagnostics.add(effect == CanonicalEffect.UNKNOWN
					? gap("CANONICAL_EFFECT_NOT_AUDITED", pattern.toString(),
						"The native output has no proved canonical range effect for this family and shape")
					: exclusion("NONCANONICAL_NATIVE_OUTPUT", pattern.toString(),
						"The native output partition axis does not match the canonical output shape"));
				return Optional.empty();
			}
		}
		try {
			return Optional.of(MemoPartition.fromRuntime(outputType, workers));
		}
		catch(IllegalArgumentException ex) {
			diagnostics.add(gap("NONCANONICAL_NATIVE_OUTPUT", pattern.toString(), ex.getMessage()));
			return Optional.empty();
		}
	}

	private static ShapeHint freshHint(ShapeHint.DiagnosticSnapshot shape, int workers,
		List<FType> runtime) {
		// The memo contract uses the complete fixed pool. A one-worker layout is
		// exactly one full range; with W>1 REP is BROADCAST and no canonical ROW/COL
		// source can be a single full partition.
		Optional<Boolean> fullSingle = Optional.of(workers == 1);
		return new ShapeHint(shape.rows(), shape.cols(), shape.blockSize(), fullSingle,
			shape.rowsA(), shape.colsA(), shape.rowsB(), shape.colsB());
	}

	private static List<InputRole> inputRoles(Hop hop) {
		List<InputRole> result = new ArrayList<>(hop.getInput().size());
		for(int i = 0; i < hop.getInput().size(); i++) {
			Hop input = hop.getInput(i);
			if(metadataInput(hop, i))
				result.add(InputRole.METADATA);
			else if(input.getDataType() == DataType.SCALAR)
				result.add(InputRole.SCALAR);
			else if(input.getDataType() == DataType.MATRIX || input.getDataType() == DataType.FRAME)
				result.add(InputRole.PAYLOAD);
			else
				result.add(InputRole.CONTROL);
		}
		return List.copyOf(result);
	}

	public static boolean metadataInput(Hop hop, int position) {
		return position == 0 && hop instanceof UnaryOp unary && unary.isMetadataOperation();
	}

	private static List<List<MemoPartition>> patternAxes(List<InputRole> roles,int workers,boolean cp) {
		List<List<MemoPartition>> axes = new ArrayList<>(roles.size());
		for(InputRole role : roles)
			axes.add((role == InputRole.METADATA || !cp && role == InputRole.PAYLOAD)
				? partitions(workers) : List.of(MemoPartition.LOCAL));
		return List.copyOf(axes);
	}

	private static List<MemoPartition> partitions(int workers) {
		return workers == 1 ? List.of(MemoPartition.LOCAL, MemoPartition.REP)
			: List.of(MemoPartition.LOCAL, MemoPartition.ROW, MemoPartition.COL, MemoPartition.REP);
	}

	@FunctionalInterface private interface PatternVisitor {
		void visit(List<MemoPartition> pattern);
	}
	private static void visitPatterns(List<List<MemoPartition>> axes,PatternVisitor visitor) {
		visitPatterns(axes,0,new ArrayList<>(),visitor);
	}
	private static void visitPatterns(List<List<MemoPartition>> axes,int position,
		List<MemoPartition> prefix,PatternVisitor visitor) {
		if(position == axes.size()) {
			visitor.visit(List.copyOf(prefix));
			return;
		}
		for(MemoPartition value : axes.get(position)) {
			prefix.add(value);
			visitPatterns(axes,position+1,prefix,visitor);
			prefix.remove(prefix.size() - 1);
		}
	}

	@FunctionalInterface private interface SupportedPatternVisitor {
		void visit(List<MemoPartition> pattern,OracleFacade.DecisionEvidence evidence,RuntimeException failure);
	}
	private static OracleFacade.SupportedPatternDiagnostics visitSupportedPatterns(
		OracleFacade.PreparedDecision prepared,List<InputRole> roles,int workers,
		ShapeHint.DiagnosticSnapshot shape,SupportedPatternVisitor visitor) {
		List<List<MemoPartition>> axes=patternAxes(roles,workers,false);
		List<List<FType>> domains=new ArrayList<>(axes.size());
		for(int position=0;position<axes.size();position++) {
			List<FType> values=new ArrayList<>(axes.get(position).size());
			for(MemoPartition partition : axes.get(position))
				values.add(runtimePartition(partition,roles.get(position),workers));
			domains.add(java.util.Collections.unmodifiableList(new ArrayList<>(values)));
		}
		return prepared.visitSupportedPatterns(domains,
			runtime -> freshHint(shape,workers,runtime),(ordinals,runtime,evidence,failure) -> {
				List<MemoPartition> pattern=new ArrayList<>(ordinals.size());
				for(int position=0;position<ordinals.size();position++)
					pattern.add(axes.get(position).get(ordinals.get(position)));
				visitor.visit(List.copyOf(pattern),evidence,failure);
				return true;
			});
	}
	private static FType runtimePartition(MemoPartition partition,InputRole role,int workers) {
		return runtimePattern(List.of(partition),List.of(role),workers).get(0);
	}

	private static List<FType> runtimePattern(List<MemoPartition> pattern,
		List<InputRole> roles, int workers) {
		List<FType> result = new ArrayList<>(pattern.size());
		for(int i = 0; i < pattern.size(); i++)
			result.add(roles.get(i) == InputRole.SCALAR || roles.get(i) == InputRole.CONTROL
				? null : pattern.get(i).runtimeType(workers));
		return result;
	}

	private static Optional<MemoPartition> aggregateBinaryVectorLocalType(Hop hop,
		List<MemoPartition> pattern, int workers) {
		if(!(hop instanceof AggBinaryOp) || hop.getDataType() != DataType.MATRIX)
			return Optional.empty();
		boolean columnVector = hop.getDim2() == 1 && hop.getDim1() > 0;
		boolean rowVector = hop.getDim1() == 1 && hop.getDim2() > 0;
		List<MemoPartition> matches = pattern.stream().filter(partition ->
			columnVector && partition == MemoPartition.ROW
				|| rowVector && partition == MemoPartition.COL
				|| workers == 1 && (columnVector || rowVector) && partition == MemoPartition.REP)
			.distinct().toList();
		return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
	}

	private enum CanonicalEffect { PROVED, EXCLUDED, UNKNOWN }

	private static CanonicalEffect indexingCanonicalEffect(IndexingOp hop, FType outputType, int workers,
		ShapeHint.DiagnosticSnapshot shape) {
		if(outputType == FType.FULL || outputType == FType.BROADCAST)
			return literalBounds(hop).isPresent() ? CanonicalEffect.PROVED : CanonicalEffect.UNKNOWN;
		if(outputType != FType.ROW && outputType != FType.COL)
			return CanonicalEffect.UNKNOWN;
		Optional<long[]> bounds = literalBounds(hop);
		if(bounds.isEmpty() || hop.getInput().isEmpty())
			return CanonicalEffect.UNKNOWN;
		long inputRows = shape.rowsA() > 0 ? shape.rowsA() : hop.getInput(0).getDim1();
		long inputCols = shape.colsA() > 0 ? shape.colsA() : hop.getInput(0).getDim2();
		if(inputRows <= 0 || inputCols <= 0 || workers < 1)
			return CanonicalEffect.UNKNOWN;
		long[] b = bounds.get();
		if(b[0] < 1 || b[1] < b[0] || b[1] > inputRows
			|| b[2] < 1 || b[3] < b[2] || b[3] > inputCols)
			return CanonicalEffect.EXCLUDED;
		long axisSize = outputType == FType.ROW ? inputRows : inputCols;
		long start = (outputType == FType.ROW ? b[0] : b[2]) - 1;
		long end = outputType == FType.ROW ? b[1] : b[3];
		long outputSize = end - start;
		if(axisSize < workers || outputSize < workers)
			return CanonicalEffect.EXCLUDED;
		for(int worker = 0; worker < workers; worker++) {
			long inputBegin = canonicalBoundary(axisSize, workers, worker);
			long inputEnd = canonicalBoundary(axisSize, workers, worker + 1);
			if(start >= inputEnd || end <= inputBegin)
				return CanonicalEffect.EXCLUDED;
			long actualBegin = Math.max(inputBegin - start, 0);
			long actualEnd = Math.min(inputEnd - start, outputSize);
			long canonicalBegin = canonicalBoundary(outputSize, workers, worker);
			long canonicalEnd = canonicalBoundary(outputSize, workers, worker + 1);
			if(actualBegin != canonicalBegin || actualEnd != canonicalEnd)
				return CanonicalEffect.EXCLUDED;
		}
		return CanonicalEffect.PROVED;
	}

	/** Mirrors CanonicalWorkerPool's ordered-remainder range construction. */
	private static long canonicalBoundary(long length, int workers, int boundary) {
		long base = length / workers;
		long remainder = length % workers;
		return boundary * base + Math.min(boundary, remainder);
	}

	private static Optional<long[]> literalBounds(IndexingOp hop) {
		if(hop.getInput().size() < 5 || !(hop.getInput(1) instanceof LiteralOp rowLower)
			|| !(hop.getInput(2) instanceof LiteralOp rowUpper)
			|| !(hop.getInput(3) instanceof LiteralOp colLower)
			|| !(hop.getInput(4) instanceof LiteralOp colUpper))
			return Optional.empty();
		try {
			return Optional.of(new long[] {rowLower.getLongValue(), rowUpper.getLongValue(),
				colLower.getLongValue(), colUpper.getLongValue()});
		}
		catch(RuntimeException ex) {
			return Optional.empty();
		}
	}

	private static CanonicalEffect appendCanonicalEffect(BinaryOp hop, FType outputType, int workers,
		ShapeHint.DiagnosticSnapshot shape) {
		if(outputType == FType.FULL || outputType == FType.BROADCAST)
			return CanonicalEffect.PROVED;
		if(outputType != FType.ROW && outputType != FType.COL || hop.getInput().size() != 2)
			return CanonicalEffect.UNKNOWN;
		Hop left = hop.getInput(0), right = hop.getInput(1);
		long lr = shape.rowsA() > 0 ? shape.rowsA() : left.getDim1();
		long lc = shape.colsA() > 0 ? shape.colsA() : left.getDim2();
		long rr = shape.rowsB() > 0 ? shape.rowsB() : right.getDim1();
		long rc = shape.colsB() > 0 ? shape.colsB() : right.getDim2();
		long or = shape.rows() > 0 ? shape.rows() : hop.getDim1();
		long oc = shape.cols() > 0 ? shape.cols() : hop.getDim2();
		if(lr <= 0 || lc <= 0 || rr <= 0 || rc <= 0 || or <= 0 || oc <= 0)
			return CanonicalEffect.UNKNOWN;
		if(hop.getOp() == OpOp2.CBIND) {
			if(lr != rr || or != lr || oc != lc + rc)
				return CanonicalEffect.EXCLUDED;
			return outputType == FType.ROW && or >= workers
				? CanonicalEffect.PROVED : CanonicalEffect.EXCLUDED;
		}
		if(lc != rc || oc != lc || or != lr + rr)
			return CanonicalEffect.EXCLUDED;
		return outputType == FType.COL && oc >= workers
			? CanonicalEffect.PROVED : CanonicalEffect.EXCLUDED;
	}

	private static CanonicalEffect ordinaryCanonicalEffect(Hop hop, List<MemoPartition> pattern,
		FType outputType, int workers, ShapeHint.DiagnosticSnapshot shape) {
		if(outputType == FType.FULL || outputType == FType.BROADCAST)
			return pattern.contains(MemoPartition.REP) ? CanonicalEffect.PROVED : CanonicalEffect.UNKNOWN;
		if(outputType != FType.ROW && outputType != FType.COL)
			return CanonicalEffect.UNKNOWN;
		long outputAxis = outputType == FType.ROW
			? positive(shape.rows(), hop.getDim1()) : positive(shape.cols(), hop.getDim2());
		if(outputAxis <= 0)
			return CanonicalEffect.UNKNOWN;
		MemoPartition required = outputType == FType.ROW ? MemoPartition.ROW : MemoPartition.COL;
		if(hop instanceof ReorgOp reorg && reorg.getOp() == org.apache.sysds.common.Types.ReOrgOp.TRANS
			&& hop.getInput().size() == 1) {
			MemoPartition opposite = outputType == FType.ROW ? MemoPartition.COL : MemoPartition.ROW;
			long inputAxis = outputType == FType.ROW
				? inputDimension(hop, shape, 0, false) : inputDimension(hop, shape, 0, true);
			return pattern.get(0) == opposite && inputAxis == outputAxis && outputAxis >= workers
				? CanonicalEffect.PROVED : CanonicalEffect.EXCLUDED;
		}
		boolean sawAxis = false;
		for(int position = 0; position < pattern.size(); position++) {
			if(pattern.get(position) != required)
				continue;
			sawAxis = true;
			long inputAxis = inputDimension(hop, shape, position, outputType == FType.ROW);
			if(inputAxis == outputAxis && outputAxis >= workers)
				return CanonicalEffect.PROVED;
			if(inputAxis <= 0)
				return CanonicalEffect.UNKNOWN;
		}
		return sawAxis ? CanonicalEffect.EXCLUDED : CanonicalEffect.UNKNOWN;
	}

	private static long inputDimension(Hop hop, ShapeHint.DiagnosticSnapshot shape,
		int position, boolean rows) {
		long hinted = position == 0 ? rows ? shape.rowsA() : shape.colsA()
			: position == 1 ? rows ? shape.rowsB() : shape.colsB() : -1;
		long compiled = position < hop.getInput().size()
			? rows ? hop.getInput(position).getDim1() : hop.getInput(position).getDim2() : -1;
		return positive(hinted, compiled);
	}

	private static long positive(long preferred, long fallback) {
		return preferred > 0 ? preferred : fallback;
	}

	private static Diagnostic gap(String code, String pattern, String message) {
		return new Diagnostic(DiagnosticKind.COVERAGE_GAP, code, pattern, message);
	}

	private static Diagnostic exclusion(String code, String pattern, String message) {
		return new Diagnostic(DiagnosticKind.POLICY_EXCLUSION, code, pattern, message);
	}

	private static boolean isBoundaryOrSource(Hop hop) {
		if(hop instanceof FunctionOp)
			return true;
		if(!(hop instanceof DataOp data))
			return false;
		OpOpData op = data.getOp();
		return op == OpOpData.FEDERATED || op == OpOpData.TRANSIENTREAD
			|| op == OpOpData.TRANSIENTWRITE || op == OpOpData.FUNCTIONOUTPUT;
	}

	private static String operatorKey(Hop hop) {
		return appendOperatorKey(hop,new StringBuilder(128)).toString();
	}
	private static StringBuilder appendOperatorKey(Hop hop,StringBuilder key) {
		key.append(hop.getClass().getName()).append('|')
			.append(hop.getOpString()).append('|').append(hop.getDataType()).append('|')
			.append(hop.getValueType()).append('|').append(hop.getDim1()).append('x')
			.append(hop.getDim2()).append('|').append(hop.getBlocksize());
		if(hop instanceof IndexingOp indexing)
			key.append("|index:").append(indexing.isRowLowerEqualsUpper()).append(':')
				.append(indexing.isColLowerEqualsUpper()).append(':').append(indexing.isAllRowsAndCols());
		if(hop instanceof AggBinaryOp aggregate)
			key.append("|aggBinary:").append(aggregate.getOuterOp()).append(':')
				.append(aggregate.getInnerOp()).append(':').append(aggregate.checkMapMultChain()).append(':')
				.append(aggregate.checkTransposeSelf()).append(':').append(aggregate.hasFederatedOutput());
		for(Hop input : hop.getInput())
			key.append("|in:").append(input.getDataType()).append(':').append(input.getValueType())
				.append(':').append(input.getDim1()).append('x').append(input.getDim2());
		return key;
	}

	private static boolean cacheable(Hop hop) {
		// These families encode their rule-relevant operator selectors in
		// getOpString; shape and ordered input kinds are included separately.
		// Less regular HOPs remain source-free, but are conservatively evaluated
		// per occurrence until OracleFacade exposes its canonical OpSig publicly.
		return hop instanceof UnaryOp || hop instanceof org.apache.sysds.hops.BinaryOp
			|| hop instanceof org.apache.sysds.hops.AggUnaryOp || hop instanceof AggBinaryOp;
	}
}

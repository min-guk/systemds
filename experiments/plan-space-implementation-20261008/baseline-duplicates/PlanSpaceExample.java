/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.GsonBuilder;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;

/** Runs the production common analysis; only source privacy acquisition is hermetic. */
public final class PlanSpaceExample {
	private static Map<String,Object> row(Object... fields) {
		Map<String,Object> result = new LinkedHashMap<>();
		for(int i = 0; i < fields.length; i += 2)
			result.put((String) fields[i], fields[i + 1]);
		return result;
	}

	private static Map<String,Object> anchor(DurableAnchorKey anchor) {
		if(anchor == null)
			return null;
		return row("fType", anchor.fType(), "partitions", anchor.partitions().stream()
			.map(p -> row("worker", p.workerId(), "begin", p.begin(), "end", p.end())).toList());
	}

	private static Map<String,Object> recordFields(Object record) throws Exception {
		Map<String,Object> result = new LinkedHashMap<>();
		for(var field : record.getClass().getRecordComponents())
			result.put(field.getName(), field.getAccessor().invoke(record));
		return result;
	}

	private static void registerPrivacy(Hop hop, Privacy privacy, Set<Hop> visited) {
		if(!visited.add(hop))
			return;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, privacy);
		for(Hop input : hop.getInput())
			registerPrivacy(input, privacy, visited);
	}

	public static void main(String[] args) throws Exception {
		if(args.length < 2 || args.length > 3)
			throw new IllegalArgumentException(
				"Usage: PlanSpaceExample script.dml PRIVACY [duplicateMergeTraceLimit]");
		String script = Files.readString(Path.of(args[0]));
		Privacy privacy = Privacy.valueOf(args[1]);
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		if(!program.getNamedNSFunctionStatementBlocks().isEmpty())
			throw new IllegalArgumentException("This small probe supports straight-line scripts only");
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for(StatementBlock block : program.getStatementBlocks()) {
			if(block.getClass() != StatementBlock.class || block.getHops() == null)
				throw new IllegalArgumentException("This small probe supports straight-line scripts only");
			for(Hop hop : block.getHops())
				registerPrivacy(hop, privacy, visited);
		}
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		if(args.length == 3)
			metrics.enableDuplicateMergeDiagnostics(Integer.parseInt(args[2]));
		List<Object> passes = new ArrayList<>();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(pass ->
			passes.add(row("phase", pass.phase(), "pass", pass.pass(), "stable", pass.stable(),
				"nodes", pass.nodeCount(), "candidates", pass.candidateCount(), "actions", pass.actionCount())),
			metrics);
		long start = System.nanoTime();
		PlacementAnalysis analysis = builder.buildAnalysis(program);
		long analysisNanos = System.nanoTime() - start;
		Map<String,Object> counters = recordFields(metrics.snapshot());
		Map<String,Object> duplicateMergeDiagnostics =
			recordFields(metrics.duplicateMergeDiagnosticsSnapshot());
		Map<CompiledHopKey,String> hops = new LinkedHashMap<>();
		Map<CandidateRuleKey,String> rules = new LinkedHashMap<>();
		Map<CandidateRealizationReference,String> sources = new LinkedHashMap<>();
		Map<RelocationActionKey,String> actions = new LinkedHashMap<>();
		Map<PlacementProofKey,String> proofs = new LinkedHashMap<>();
		List<Object> hopRows = new ArrayList<>();
		for(var occurrence : analysis.compiledHopOccurrences()) {
			Hop hop = occurrence.hop();
			String id = "H" + (hops.size() + 1);
			hops.put(occurrence.key(), id);
			hopRows.add(row("id", id, "hopId", hop.getHopID(), "name", hop.getName(),
				"op", hop.getOpString(), "line", hop.getBeginLine(), "dataType", hop.getDataType(),
				"rows", hop.getDim1(), "cols", hop.getDim2(),
				"inputHopIds", hop.getInput().stream().map(Hop::getHopID).toList()));
		}
		var facts = analysis.candidateRuleFacts().orderedFacts();
		for(var fact : facts) {
			rules.put(fact.key(), "R" + (rules.size() + 1));
			for(var emission : fact.allowedEmissionFacts())
				for(var realization : emission.realizations())
					sources.computeIfAbsent(CandidateRealizationReference.of(fact.key(), realization),
						key -> "S" + (sources.size() + 1));
		}
		List<Object> ruleRows = new ArrayList<>();
		List<Object> realizationRows = new ArrayList<>();
		Set<CandidateRealizationReference> unresolved = new LinkedHashSet<>();
		for(var fact : facts) {
			String rule = rules.get(fact.key());
			ruleRows.add(row("id", rule, "owner", hops.get(fact.key().parentOccurrence()),
				"inputTypes", fact.key().orderedInputs().stream().map(x -> x.normalizedSignature()).toList(),
				"status", fact.status(), "failure", fact.failureCode(), "emissions", fact.allowedEmissionFacts().size()));
			for(var emission : fact.allowedEmissionFacts()) {
				for(var realization : emission.realizations()) {
					List<Object> clauses = new ArrayList<>();
					Map<Integer,Set<Object>> bindingsByInput = new LinkedHashMap<>();
					for(var clause : realization.supportClauses()) {
						List<Object> bindings = new ArrayList<>();
						for(var binding : clause.inputBindings()) {
							String source = sources.get(binding.source());
							if(source == null)
								unresolved.add(binding.source());
							String action = binding.relocationAction() == null ? null
								: actions.computeIfAbsent(binding.relocationAction(), k -> "A" + (actions.size() + 1));
							bindings.add(row("input", binding.inputPosition(), "source", source,
								"kind", binding.kind(), "action", action));
							bindingsByInput.computeIfAbsent(binding.inputPosition(), k -> new LinkedHashSet<>()).add(binding);
						}
						clauses.add(row("id", "C" + (clauses.size() + 1), "bindings", bindings,
							"proofIds", clause.proofDependencies().stream().map(p ->
								proofs.computeIfAbsent(p, k -> "P" + (proofs.size() + 1))).toList(),
							"nativePool", anchor(clause.nativeWorkerPoolWitness()),
							"nativeLayoutExact", clause.nativeWorkerPoolLayoutExact()));
					}
					Map<Integer,Integer> bindingCounts = new LinkedHashMap<>();
					bindingsByInput.forEach((position, bindings) -> bindingCounts.put(position, bindings.size()));
					var key = realization.key();
					realizationRows.add(row("id", sources.get(CandidateRealizationReference.of(fact.key(), realization)),
						"rule", rule, "owner", hops.get(fact.key().parentOccurrence()),
						"state", key.emissionState().placementState().normalizedSignature(),
						"layout", key.layoutKind(), "anchor", anchor(key.durableAnchor()),
						"executionFType", emission.executionFType(),
						"derivedOutputMaterialization", emission.derivedFoutAction() != null,
						"supportCount", clauses.size(), "uniqueBindingsPerInput", bindingCounts, "supports", clauses));
				}
			}
		}
		List<Object> actionRows = new ArrayList<>();
		actions.forEach((key, id) -> actionRows.add(row("id", id,
			"sourceVariable", key.sourceValueVersion().lexicalVariable(),
			"target", key.targetPlacement().normalizedSignature(), "anchor", anchor(key.durableAnchor()),
			"consumers", key.compatibleConsumers().stream().map(hops::get).toList())));
		List<Object> proofRows = new ArrayList<>();
		proofs.forEach((key, id) -> proofRows.add(row("id", id, "kind", key.kind(),
			"owner", hops.get(key.owner()), "signature", key.normalizedSignature())));
		if(!unresolved.isEmpty())
			throw new IllegalStateException("Unresolved published source references: " + unresolved.size());
		System.out.println(new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(row(
			"script", script, "privacy", privacy, "mode", "production-common-analysis/hermetic-source-metadata",
			"analysisMillis", analysisNanos / 1_000_000.0, "hops", hopRows, "rules", ruleRows,
			"realizations", realizationRows, "inputBindingRelocations", actionRows, "proofs", proofRows,
			"fixedPointPasses", passes,
			"cumulativeWorkCounters", counters,
			"duplicateMergeDiagnostics", duplicateMergeDiagnostics)));
	}
}

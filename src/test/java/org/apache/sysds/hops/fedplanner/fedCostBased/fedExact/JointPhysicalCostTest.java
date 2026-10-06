/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.Direction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** The new legal plans must also have a physical price and a durable selected receipt. */
public class JointPhysicalCostTest {
	private static final String SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23337/X2\"),"
		+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
		+ "Y=federated(addresses=list(\"localhost:23335/Y1\",\"localhost:23338/Y2\"),"
		+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
		+ "W=federated(addresses=list(\"localhost:23336/W1\",\"localhost:23339/W2\"),"
		+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
		+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));";
	private static final String UNEQUAL_POOL_SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23334/X2\"),"
		+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
		+ "Y=federated(addresses=list(\"localhost:23335/Y1\",\"localhost:23338/Y2\"),"
		+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
		+ "U=federated(addresses=list(\"localhost:23336/U1\",\"localhost:23339/U2\"),"
		+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
		+ "p=as.scalar(rand(rows=1,cols=1));";

	@Test
	public void correlatedMapExecutionHasPositiveCostAndProjectsToAPlan() throws Exception {
		var analysis = analysis(SOURCES
			+ "if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}C=A+B;print(sum(C));");
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var optimized = ExactPhysicalOptimizer.optimize(model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var selected = ExactPhysicalSelection.create(model, optimized);
		var projected = ExactPhysicalPlacementProjector.project(selected);
		Assert.assertTrue(projected.normalizedResult().selectedCandidateSelections().stream().anyMatch(receipt ->
			analysis.hop(receipt.rule().parentOccurrence()).orElseThrow() instanceof BinaryOp
				&& receipt.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP));
		double jointCost = surface.contributions().stream()
			.filter(contribution -> contribution.id().contains("JOINT_VALUE_MAP_EXECUTION"))
			.mapToDouble(contribution -> surface.evaluateContributionCanonical(contribution,
				optimized.solverResult().assignmentInVariableOrder())).sum();
		Assert.assertTrue("selected correlated FED work cannot be free: " + jointCost, jointCost > 0);
		double invariantCost = surface.contributions().stream()
			.filter(contribution -> contribution.id().contains("JOINT_VALUE_MAP_EXECUTION_INVARIANT"))
			.mapToDouble(contribution -> surface.evaluateContributionCanonical(contribution,
				optimized.solverResult().assignmentInVariableOrder())).sum();
		Assert.assertTrue("At least the one-input aggregation has the same physical price on either pool"
			+ " and must retain its nonzero unary charge", invariantCost > 0);
	}

	@Test
	public void nestedBranchCostUsesBranchEventsInsteadOfTupleCount() throws Exception {
		var analysis = analysis(SOURCES
			+ "if(p>0.5){A=X;B=X;}else{if(q>0.5){A=Y;B=Y;}else{A=W;B=W;}}"
			+ "C=A+B;print(sum(C));");
		var model = ExactPhysicalModel.build(analysis);
		var solved = model.solveLegalityOnly(ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected = new IdentityHashMap<>();
		for(int index = 0; index < model.domains().size(); index++) {
			var domain = model.domains().get(index);
			selected.put(domain.node().key(), domain.alternatives().get(solved.assignmentInVariableOrder().get(index)));
		}
		var consumer = selected.values().stream().filter(alternative ->
			analysis.hop(alternative.decision()).orElseThrow() instanceof BinaryOp
				&& alternative.realization() != null
				&& alternative.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP).findFirst().orElseThrow();
		List<JointPhysicalCostRows.Row> rows = new JointPhysicalCostRows(analysis).executionRows(consumer, selected);
		Assert.assertEquals("three reachable definitions", 3, rows.size());
		double cost = JointPhysicalCostRows.expectedUnit(analysis.executionFrequencyFacts(), consumer.decision(),
			rows, row -> {
				String endpoint = row.inputs().get(0).pool().partitions().get(0).workerId();
				return endpoint.contains("23334") ? 2 : endpoint.contains("23335") ? 4 : 8;
			});
		Assert.assertEquals("0.5*2 + 0.25*4 + 0.25*8, not the mean of three tuples", 4, cost, 1e-12);
	}

	@Test
	public void unresolvedValueMapDownloadUsesNonzeroConservativePoolBound() throws Exception {
		var analysis = mixedAnalysis(UNEQUAL_POOL_SOURCES
			+ "if(p>0.5){A=X;}else{A=Y;}C=A+1;"
			+ "write(C,\"/tmp/value-map-bound\",format=\"binary\");print(sum(U));");
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var rows = new JointPhysicalCostRows(analysis);
		ExactPhysicalModel.Alternative mapped = model.domains().stream()
			.filter(domain -> analysis.hop(domain.node().key()).orElseThrow() instanceof BinaryOp)
			.flatMap(domain -> domain.alternatives().stream())
			.filter(alternative -> alternative.realization() != null
				&& alternative.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP)
			.filter(alternative -> workerCounts(JointPhysicalCostRows.possiblePools(analysis,
				alternative.realization(), alternative.supportClause())).equals(Set.of(1, 2)))
			.findFirst().orElseThrow(() -> new AssertionError(
				"VALUE_MAP alternative over one- and two-worker pools missing: "
					+ model.domains().stream().flatMap(domain -> domain.alternatives().stream())
						.filter(alternative -> alternative.realization() != null
							&& alternative.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP)
						.map(alternative -> alternative.decision().normalizedSignature() + "="
							+ workerCounts(JointPhysicalCostRows.possiblePools(analysis,
								alternative.realization(), alternative.supportClause()))).toList()));

		Map<CompiledHopKey,ExactPhysicalModel.Alternative> producerOnly = new IdentityHashMap<>();
		producerOnly.put(mapped.decision(), mapped);
		Assert.assertTrue("Without reader/source-owner choices the exact dynamic map must remain unresolved",
			rows.valueRows(mapped, producerOnly).isEmpty());

		Set<DurableAnchorKey> pools = JointPhysicalCostRows.possiblePools(
			analysis, mapped.realization(), mapped.supportClause());
		double bytes = productionTransferBytes(analysis, mapped.decision());
		List<Double> reachablePrices = pools.stream().map(pool ->
			FederatedCostModel.computeReusableMaterializationDownloadCost(
				bytes, pool.fType(), workerCount(pool))).toList();
		double conservativeBound = reachablePrices.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
		Assert.assertEquals("Fixture must expose different reachable download prices",
			2, new LinkedHashSet<>(reachablePrices).size());
		Assert.assertTrue("A selected reusable materialization must never become free", conservativeBound > 0);
		for(double reachable : reachablePrices)
			Assert.assertTrue("Conservative fallback must not underprice any reachable dynamic map",
				conservativeBound >= reachable);

		var source = model.domains().stream().filter(domain -> domain.node().key() == mapped.decision())
			.findFirst().orElseThrow();
		var endpoint = surface.transferKeys().stream().filter(key -> key.direction() == Direction.DOWNLOAD)
			.flatMap(key -> key.endpoints().stream()).filter(edge -> edge.producer() == mapped.decision())
			.findFirst().orElseThrow(() -> new AssertionError("Selected VALUE_MAP download missing"));
		var consumer = model.domains().stream().filter(domain -> domain.node().key() == endpoint.consumer())
			.findFirst().orElseThrow();
		int local = java.util.stream.IntStream.range(0, consumer.alternatives().size()).filter(index -> {
			var alternative = consumer.alternatives().get(index);
			return alternative.state().execType() == ExecType.CP
				&& alternative.state().output() == FederatedOutput.LOUT;
		}).findFirst().orElseThrow();
		List<Integer> assignment = new ArrayList<>();
		model.domains().forEach(ignored -> assignment.add(0));
		assignment.set(model.domains().indexOf(source), source.alternatives().indexOf(mapped));
		assignment.set(model.domains().indexOf(consumer), local);
		double charged = surface.contributions().stream().filter(contribution ->
			contribution.factor().scope().contains(source.variable())
				&& contribution.factor().scope().contains(consumer.variable()))
			.mapToDouble(contribution -> surface.evaluateContributionCanonical(contribution, assignment)).sum();
		Assert.assertEquals("One cached local materialization must charge the conservative dynamic-map bound",
			conservativeBound, charged, Math.max(1e-12, conservativeBound * 1e-12));
	}

	private static Set<Integer> workerCounts(Set<DurableAnchorKey> pools) {
		Set<Integer> result = new LinkedHashSet<>();
		pools.forEach(pool -> result.add(workerCount(pool)));
		return result;
	}

	private static int workerCount(DurableAnchorKey pool) {
		return (int)pool.partitions().stream().map(partition ->
			FederationUtils.canonicalFederatedWorkerAddress(partition.workerId())).distinct().count();
	}

	private static double productionTransferBytes(PlacementAnalysis analysis, CompiledHopKey source)
		throws Exception {
		var estimates = PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		var method = java.util.Arrays.stream(ExactPhysicalCostModel.class.getDeclaredMethods())
			.filter(candidate -> candidate.getName().equals("estimatedBytes")
				&& candidate.getParameterCount() == 4).findFirst().orElseThrow();
		method.setAccessible(true);
		return (double)method.invoke(null, analysis, estimates, source, analysis.hop(source).orElseThrow());
	}

	private static PlacementAnalysis analysis(String script) throws Exception {
		return analysis(script, Privacy.PRIVATE_AGGREGATE);
	}

	private static PlacementAnalysis analysis(String script, Privacy privacy) throws Exception {
		DMLProgram program = parsedProgram(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static PlacementAnalysis mixedAnalysis(String script) throws Exception {
		DMLProgram program = parsedProgram(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		Set<Hop> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
			.flatMap(block -> block.getHops().stream()).forEach(root -> protectSourceU(root, visited));
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static void protectSourceU(Hop hop, Set<Hop> visited) {
		if(hop == null || !visited.add(hop)) return;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED && "U".equals(data.getName()))
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, Privacy.PRIVATE_AGGREGATE);
		hop.getInput().forEach(input -> protectSourceU(input, visited));
	}

	private static DMLProgram parsedProgram(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}

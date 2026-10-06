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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** GET ownership follows one runtime MatrixObject through function-input aliases. */
public class ExactFunctionAliasGetCostTest {
	@Test
	public void callerAndCalleeCollectOneStablePublicValueOnce() throws Exception {
		assertGetCopies(analyze(false), List.of("Y"), 1);
	}

	@Test
	public void callerAndNestedCalleesCollectOneStablePublicValueOnce() throws Exception {
		assertGetCopies(analyze(true), List.of("Y"), 1);
	}

	@Test
	public void repeatedCallsReuseStableValueDownloadedBeforeTheLoop() throws Exception {
		assertGetCopies(analyzeLoop(false), List.of("Y"), 1);
	}

	@Test
	public void valueCreatedInEachLoopIterationRetainsRepeatedGetCost() throws Exception {
		assertGetCopies(analyzeLoop(true), List.of("Y"), 3);
	}

	@Test
	public void distinctSourceOriginsKeepDistinctGetCharges() throws Exception {
		assertGetCopies(analyzeDistinctOrigins(), List.of("Y", "W"), 2);
	}

	@Test
	public void valueCreatedOncePerRepeatedOuterInvocationKeepsDistinctGetCharges() throws Exception {
		assertGetCopies(analyzeRepeatedOuterCreation(), List.of("P"), 2);
	}

	@Test
	public void passThroughFunctionReturnReusesDownloadedMatrixObject() throws Exception {
		assertGetCopies(analyzeFunctionReturn(false, false), List.of("Y"), 1);
	}

	@Test
	public void nestedPassThroughFunctionReturnReusesDownloadedMatrixObject() throws Exception {
		assertGetCopies(analyzeFunctionReturn(true, false), List.of("Y"), 1);
	}

	@Test
	public void repeatedPassThroughReturnsReuseDownloadedMatrixObject() throws Exception {
		assertGetCopies(analyzeFunctionReturn(false, true), List.of("Y"), 1);
	}

	@Test
	public void freshlyComputedFunctionReturnKeepsDistinctGetCharge() throws Exception {
		assertAllFedScalarGetCopies(analyzeFreshFunctionReturn(false), 1);
	}

	@Test
	public void repeatedFreshFunctionReturnsKeepPerInvocationGetCharges() throws Exception {
		assertAllFedScalarGetCopies(analyzeFreshFunctionReturn(true), 2);
	}

	private static void assertAllFedScalarGetCopies(PlacementAnalysis analysis, int copies) {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.exactSolverHardFactors());
		factors.addAll(surface.exactSolverFactors());
		Set<ExactPhysicalModel.DecisionDomain> scalarAggregates = new LinkedHashSet<>();
		for(var domain : model.domains()) {
			Hop hop = analysis.hop(domain.node().key()).orElse(null);
			boolean hasCp = domain.alternatives().stream()
				.anyMatch(alternative -> alternative.state().execType() == ExecType.CP);
			if(hop instanceof org.apache.sysds.hops.AggUnaryOp && hop.getDataType().isScalar() && hasCp) {
				scalarAggregates.add(domain);
				force(factors, domain, alternative -> alternative.state().execType() == ExecType.CP);
				continue;
			}
			boolean hasDirectFout = domain.alternatives().stream().anyMatch(alternative ->
				alternative.state().execType() == ExecType.FED
					&& alternative.state().output() == FederatedOutput.FOUT && direct(alternative));
			if(hasDirectFout && hop != null && hop.getDataType() != null && hop.getDataType().isMatrix())
				force(factors, domain, alternative -> alternative.state().execType() == ExecType.FED
					&& alternative.state().output() == FederatedOutput.FOUT && direct(alternative));
		}
		Assert.assertFalse("Fixture must contain a scalar aggregate consumer", scalarAggregates.isEmpty());
		ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(surface.exactSolverVariables(),
			factors, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		List<Integer> assignment = List.copyOf(
			result.assignmentInVariableOrder().subList(0, model.variables().size()));
		Assert.assertTrue("Forced return assignment must satisfy every production hard factor",
			Double.isFinite(RegionalSearchProblem.evaluateFactors(
				model.variables(), model.hardFactors(), assignment)));
		double actual = surface.contributions().stream()
			.filter(contribution -> contribution.factor().scope().size() > 1)
			.filter(contribution -> contribution.factor().scope().stream().anyMatch(variable ->
				scalarAggregates.stream().anyMatch(aggregate -> aggregate.variable() == variable)))
			.mapToDouble(contribution -> surface.evaluateContributionCanonical(contribution, assignment)).sum();
		var source = model.domains().stream().filter(domain -> "Y".equals(hopName(analysis, domain.node().key())))
			.findFirst().orElseThrow();
		int selected = assignment.get(model.domains().indexOf(source));
		double expected = FederatedCostModel.computeReusableMaterializationDownloadCost(
			PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, source.node().key()),
			source.alternatives().get(selected).state().fType(), 2);
		Assert.assertEquals("Fresh return values retain one MatrixObject per producing invocation",
			copies * expected, actual, Math.max(1e-12, expected * 1e-12));
	}

	private static void assertGetCopies(PlacementAnalysis analysis, List<String> sourceNames, int copies) {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey> roots =
			new ArrayList<>(analysis.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(input -> sourceNames.contains(hopName(analysis, input.sourceArgument())))
			.map(input -> input.sourceArgument()).toList());
		if(roots.isEmpty())
			roots.addAll(analysis.logicalInlinedFunctionInputsInCanonicalOrder().stream()
				.flatMap(input -> input.sourceArgument().stream())
				.filter(source -> sourceNames.contains(hopName(analysis, source))).toList());
		Assert.assertEquals("Each requested source must enter one function", sourceNames.size(), roots.size());
		Set<ExactPhysicalModel.DecisionDomain> aliases = new LinkedHashSet<>();
		for(var root : roots)
			aliases.addAll(aliasDomains(analysis, model, root));
		Set<ExactPhysicalModel.DecisionDomain> consumers = new LinkedHashSet<>();
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(aliases.stream().noneMatch(domain -> domain.node().key() == edge.producer()))
				continue;
			Hop consumer = analysis.hop(edge.consumer()).orElse(null);
			if(consumer != null && !(consumer instanceof DataOp)
				&& !analysis.isDmlFunctionCallBoundary(edge.consumer()))
				consumers.add(domain(model, edge.consumer()));
		}
		Assert.assertTrue("Fixture must contain enough local consumers to observe the GET lifetime",
			consumers.size() >= 2);

		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.exactSolverHardFactors());
		factors.addAll(surface.exactSolverFactors());
		for(var alias : aliases)
			force(factors, alias, alternative -> alternative.state().execType() == ExecType.FED
				&& alternative.state().output() == FederatedOutput.FOUT && direct(alternative));
		for(var consumer : consumers)
			force(factors, consumer, alternative -> alternative.state().execType() == ExecType.CP);
		ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(surface.exactSolverVariables(),
			factors, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		List<Integer> assignment = List.copyOf(
			result.assignmentInVariableOrder().subList(0, model.variables().size()));
		Assert.assertTrue("Forced function-alias assignment must satisfy every production hard factor",
			Double.isFinite(RegionalSearchProblem.evaluateFactors(
				model.variables(), model.hardFactors(), assignment)));

		double actual = surface.contributions().stream()
			.filter(contribution -> contribution.factor().scope().stream()
				.anyMatch(variable -> aliases.stream().anyMatch(alias -> alias.variable() == variable)))
			.filter(contribution -> contribution.factor().scope().stream()
				.anyMatch(variable -> consumers.stream().anyMatch(consumer -> consumer.variable() == variable)))
			.mapToDouble(contribution -> surface.evaluateContributionCanonical(contribution, assignment)).sum();
		var source = domain(model, roots.get(0));
		int selected = assignment.get(model.domains().indexOf(source));
		double expected = FederatedCostModel.computeReusableMaterializationDownloadCost(
			PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, roots.get(0)),
			source.alternatives().get(selected).state().fType(), 2);
		Assert.assertEquals("GET cost follows the number of runtime MatrixObjects, not function contexts",
			copies * expected, actual, Math.max(1e-12, expected * 1e-12));
	}

	private static Set<ExactPhysicalModel.DecisionDomain> aliasDomains(PlacementAnalysis analysis,
		ExactPhysicalModel model,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey origin) {
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey> keys =
			Collections.newSetFromMap(new IdentityHashMap<>());
		keys.add(origin);
		boolean changed;
		do {
			changed = false;
			for(var input : analysis.logicalFunctionInputsInCanonicalOrder())
				if(keys.contains(input.sourceArgument()) || keys.contains(input.targetRead())) {
					changed |= keys.add(input.sourceArgument());
					changed |= keys.add(input.targetRead());
				}
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				Hop consumer = analysis.hop(edge.consumer()).orElse(null);
				if(consumer instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE
					&& (keys.contains(edge.producer()) || keys.contains(edge.consumer()))) {
					changed |= keys.add(edge.producer());
					changed |= keys.add(edge.consumer());
				}
			}
			for(var input : analysis.logicalTransientInputsInCanonicalOrder())
				if(keys.contains(input.sourceWrite()) || keys.contains(input.targetRead())) {
					changed |= keys.add(input.sourceWrite());
					changed |= keys.add(input.targetRead());
				}
			for(var constraint : analysis.graph().constraints())
				if((constraint.evidence().startsWith("function-result:")
					|| constraint.evidence().startsWith("cfg-function-output-value:"))
					&& (keys.contains(constraint.left()) || keys.contains(constraint.right()))) {
					changed |= keys.add(constraint.left());
					changed |= keys.add(constraint.right());
				}
		}
		while(changed);
		Set<ExactPhysicalModel.DecisionDomain> domains = new LinkedHashSet<>();
		for(var key : keys)
			model.domains().stream().filter(domain -> domain.node().key() == key)
				.findFirst().ifPresent(domains::add);
		return domains;
	}

	private static void force(List<ExactCategoricalSolver.Factor> factors,
		ExactPhysicalModel.DecisionDomain domain, Predicate<ExactPhysicalModel.Alternative> predicate) {
		boolean[] accepted = new boolean[domain.alternatives().size()];
		for(int index = 0; index < accepted.length; index++)
			accepted[index] = predicate.test(domain.alternatives().get(index));
		Assert.assertTrue("Required forced state missing for " + domain.node().key(),
			java.util.stream.IntStream.range(0, accepted.length).anyMatch(index -> accepted[index]));
		factors.add(ExactCategoricalSolver.Factor.lazy(List.of(domain.variable()),
			values -> accepted[values[0]] ? 0d : Double.POSITIVE_INFINITY));
	}

	private static boolean direct(ExactPhysicalModel.Alternative alternative) {
		return alternative.relocationAction() == null && alternative.derivedFoutAction() == null
			&& alternative.inputAuthorities().stream().noneMatch(authority ->
				authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION);
	}

	private static ExactPhysicalModel.DecisionDomain domain(ExactPhysicalModel model,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key) {
		return model.domains().stream().filter(domain -> domain.node().key() == key)
			.findFirst().orElseThrow();
	}

	private static String hopName(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key) {
		return analysis.hop(key).map(Hop::getName).orElse("");
	}

	private static PlacementAnalysis analyze(boolean nested) throws Exception {
		String functions = nested ? """
			inner=function(matrix[double] A) return(matrix[double] B) {
			  B=A+0; j=1; while(j<2) { B=B+0; j=j+1; }
			}
			outer=function(matrix[double] A) return(matrix[double] B) {
			  B=inner(A); k=1; while(k<2) { B=B+0; k=k+1; }
			}
			""" : """
			outer=function(matrix[double] A) return(matrix[double] B) {
			  B=A+0; k=1; while(k<2) { B=B+0; k=k+1; }
			}
			""";
		String script = functions + """
			X_LOCAL=rand(rows=8,cols=4,seed=7);
			X=federated(local_matrix=X_LOCAL, addresses=list("localhost:1234","localhost:1235"),
			 ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));
			Y_LOCAL=rand(rows=8,cols=1,seed=8);
			Y=federated(local_matrix=Y_LOCAL, addresses=list("localhost:2234","localhost:2235"),
			 ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));
			P=colSums(X);
			Z=outer(Y);
			print(sum(Z));
			print(sum(P));
			print(sum(Y));
			""";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		setFederatedSourcePrivacy(program, "Y", Privacy.PUBLIC);
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}

	private static PlacementAnalysis analyzeLoop(boolean createdInsideLoop) throws Exception {
		String script = """
			outer=function(matrix[double] A) return(matrix[double] B) {
			  B=A+0; k=1; while(k<2) { B=B+0; k=k+1; }
			}
			X_LOCAL=rand(rows=8,cols=4,seed=7);
			X=federated(local_matrix=X_LOCAL, addresses=list("localhost:1234","localhost:1235"),
			 ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));
			""" + (createdInsideLoop ? """
			for(i in 1:3) {
			  Y=rowSums(X+i);
			  Z=outer(Y);
			  print(sum(Z));
			  print(sum(Y));
			}
			""" : """
			Y_LOCAL=rand(rows=8,cols=1,seed=8);
			Y=federated(local_matrix=Y_LOCAL, addresses=list("localhost:2234","localhost:2235"),
			 ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));
			for(i in 1:3) {
			  Z=outer(Y);
			  print(sum(Z));
			  print(sum(Y));
			}
			""");
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		if(!createdInsideLoop)
			setFederatedSourcePrivacy(program, "Y", Privacy.PUBLIC);
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}

	private static PlacementAnalysis analyzeDistinctOrigins() throws Exception {
		String script = """
			left=function(matrix[double] A) return(matrix[double] B) {
			  B=A+0; i=1; while(i<2) { B=B+0; i=i+1; }
			}
			right=function(matrix[double] A) return(matrix[double] B) {
			  B=A+0; i=1; while(i<2) { B=B+0; i=i+1; }
			}
			Y_LOCAL=rand(rows=8,cols=1,seed=8);
			Y=federated(local_matrix=Y_LOCAL, addresses=list("localhost:2234","localhost:2235"),
			 ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));
			W_LOCAL=rand(rows=8,cols=1,seed=9);
			W=federated(local_matrix=W_LOCAL, addresses=list("localhost:3234","localhost:3235"),
			 ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));
			Z=left(Y);
			Q=right(W);
			print(sum(Y)+sum(W)+sum(Z)+sum(Q));
			""";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		setFederatedSourcePrivacy(program, "Y", Privacy.PUBLIC);
		setFederatedSourcePrivacy(program, "W", Privacy.PUBLIC);
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}

	private static PlacementAnalysis analyzeRepeatedOuterCreation() throws Exception {
		String script = """
			inner=function(matrix[double] A) return(matrix[double] B) {
			  B=A+0; i=1; while(i<2) { B=B+0; i=i+1; }
			}
			outer=function(matrix[double] A) return(matrix[double] B) {
			  P=rowSums(A+1);
			  Q=inner(P);
			  B=Q+sum(P);
			}
			X_LOCAL=rand(rows=8,cols=4,seed=7);
			X=federated(local_matrix=X_LOCAL, addresses=list("localhost:1234","localhost:1235"),
			 ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));
			for(j in 1:2) {
			  Z=outer(X);
			  print(sum(Z));
			}
			""";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}

	private static PlacementAnalysis analyzeFunctionReturn(boolean nested, boolean repeated) throws Exception {
		String functions = nested ? """
			inner=function(matrix[double] A) return(matrix[double] B) {
			  print(sum(A)); B=A; i=1; while(i<2) { i=i+1; }
			}
			outer=function(matrix[double] A) return(matrix[double] B) {
			  B=inner(A); i=1; while(i<2) { i=i+1; }
			}
			""" : """
			outer=function(matrix[double] A) return(matrix[double] B) {
			  print(sum(A)); B=A; i=1; while(i<2) { i=i+1; }
			}
			""";
		String calls = repeated ? """
			for(j in 1:2) {
			  Z=outer(Y);
			  print(sum(Z));
			}
			""" : """
			Z=outer(Y);
			print(sum(Z));
			""";
		return analyzeScript(functions + privateAggregateSource() + calls);
	}

	private static PlacementAnalysis analyzeFreshFunctionReturn(boolean repeated) throws Exception {
		String calls = repeated ? """
			for(j in 1:2) {
			  Z=outer(Y);
			  print(sum(Z));
			}
			""" : """
			Z=outer(Y);
			print(sum(Z));
			""";
		return analyzeScript("""
			outer=function(matrix[double] A) return(matrix[double] B) {
			  B=A+1; print(sum(B)); i=1; while(i<2) { i=i+1; }
			}
			""" + privateAggregateSource() + calls);
	}

	private static String privateAggregateSource() {
		return """
			X_LOCAL=rand(rows=8,cols=4,seed=7);
			X=federated(local_matrix=X_LOCAL, addresses=list("localhost:1234","localhost:1235"),
			 ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));
			Y=rowSums(X);
			""";
	}

	private static PlacementAnalysis analyzeScript(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}

	private static void setFederatedSourcePrivacy(DMLProgram program, String sourceName, Privacy privacy) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
			.forEach(block -> pending.addAll(block.getHops()));
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		int matches = 0;
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop)) continue;
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
				&& sourceName.equals(data.getName())) {
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, privacy);
				matches++;
			}
			pending.addAll(hop.getInput());
		}
		Assert.assertEquals(1, matches);
	}
}

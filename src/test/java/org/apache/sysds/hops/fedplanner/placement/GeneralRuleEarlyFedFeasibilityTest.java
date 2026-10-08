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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.PartialTruth;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class GeneralRuleEarlyFedFeasibilityTest {
	private static final PlacementCandidateGenerator.GenerationPrivacy FED_REQUIRED =
		new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PRIVATE, Set.of(0));
	private static final List<FType> MATRIX_DOMAIN = Arrays.asList(
		null, FType.ROW, FType.COL, FType.FULL, FType.PART, FType.BROADCAST);

	@Test
	public void ordinaryUnaryBinaryMmAndAggregateMatchExhaustiveForwardOracle() {
		DataOp a = matrix("A", 8, 4);
		DataOp b = matrix("B", 4, 6);
		assertGeneralParity(new UnaryOp("exp", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, a), List.of(MATRIX_DOMAIN));
		assertGeneralParity(new BinaryOp("plus", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, a, matrix("C", 8, 4)), List.of(MATRIX_DOMAIN, MATRIX_DOMAIN));
		assertGeneralParity(new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, a, b), List.of(MATRIX_DOMAIN, MATRIX_DOMAIN));
		assertGeneralParity(new AggUnaryOp("rowsums", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, a), List.of(MATRIX_DOMAIN));
	}

	@Test
	public void fixedSeedDomainOrdersRemainDeterministicAndEquivalent() {
		java.util.Random random = new java.util.Random(0x51a7eL);
		for(int iteration = 0; iteration < 12; iteration++) {
			List<FType> left = new ArrayList<>(MATRIX_DOMAIN);
			List<FType> right = new ArrayList<>(MATRIX_DOMAIN);
			java.util.Collections.shuffle(left, random);
			java.util.Collections.shuffle(right, random);
			assertGeneralParity(new BinaryOp("plus-" + iteration, DataType.MATRIX,
				ValueType.FP64, OpOp2.PLUS, matrix("L" + iteration, 8, 4),
				matrix("R" + iteration, 8, 4)), List.of(left, right));
		}
	}

	@Test
	public void publicCpAndUnknownFunctionKeepTheCompleteProduct() {
		BinaryOp plus = new BinaryOp("public-plus", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, matrix("PA", 8, 4), matrix("PB", 8, 4));
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.ROW, FType.FULL), Arrays.asList(null, FType.COL));
		OracleFacade.PreparedDecision prepared = facade().prepareDecision(plus);
		var early = prepared.prepareEarlyFedFeasibility(domains,
			ignored -> exactShape()).orElseThrow();
		List<List<FType>> publicRows = new ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(
			domains, null, prepared, early, publicRows::add, null);
		Assert.assertEquals("without protected input authority CP tuples must remain", 6,
			publicRows.size());

		FunctionOp call = new FunctionOp(FunctionType.DML, "main", "custom",
			new String[] {"X", "Y"}, List.of(matrix("UX", 8, 4), matrix("UY", 8, 4)),
			new String[] {"Z"}, true);
		OracleFacade.PreparedDecision udf = facade().prepareDecision(call);
		Assert.assertTrue("custom/UDF rules without dependency authority skip generic partial search",
			udf.prepareEarlyFedFeasibility(domains, ignored -> exactShape()).isEmpty());
		List<List<FType>> udfRows = new ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(
			domains, FED_REQUIRED, udf, udfRows::add, null);
		Assert.assertEquals("only the protected-null mask applies to UDF tuples", 4, udfRows.size());
	}

	@Test
	public void exactShapeProviderAndUndeclaredShapeFactsFailClosed() {
		AtomicInteger shapeRequests = new AtomicInteger();
		RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
		registry.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
			@Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
			@Override public java.util.Optional<DecisionDependencies> decisionDependencies(OpSig sig) {
				return java.util.Optional.of(new DecisionDependencies(Set.of(0), Set.of(0), Set.of("rows")));
			}
			@Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				boolean fed = inputs.get(0) == FType.ROW && hint.rows() == 8;
				return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
					.exec(fed ? ExecType.FED : ExecType.CP)
					.placement(fed ? FederatedOutput.FOUT : FederatedOutput.LOUT)
					.fout(fed, fed ? FType.ROW : null).reason(fed ? ReasonCode.OK : ReasonCode.NO_FED_INPUT)
					.build();
			}
		});
		BinaryOp plus = new BinaryOp("shape-plus", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, matrix("SA", 8, 4), matrix("SB", 8, 4));
		OracleFacade.PreparedDecision prepared = new OracleFacade(registry).prepareDecision(plus);
		List<List<FType>> domains = List.of(List.of(FType.ROW, FType.COL), List.of(FType.FULL));
		var early = prepared.prepareEarlyFedFeasibility(domains, inputs -> {
			shapeRequests.incrementAndGet();
			return exactShape();
		}).orElseThrow();
		Assert.assertEquals(org.apache.sysds.hops.fedplanner.rules.RulesApi.PartialTruth.FEASIBLE,
			early.fedFeasibility(partial(Arrays.asList(FType.ROW, null), Set.of(0))));
		Assert.assertTrue(shapeRequests.get() > 0);

		RulesCore.RuleRegistry invalid = new RulesCore.RuleRegistry();
		invalid.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
			@Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
			@Override public java.util.Optional<DecisionDependencies> decisionDependencies(OpSig sig) {
				return java.util.Optional.of(new DecisionDependencies(Set.of(0), Set.of(), Set.of("rows")));
			}
			@Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				hint.cols();
				return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
					.exec(ExecType.FED).placement(FederatedOutput.FOUT).fout(true, FType.ROW)
					.reason(ReasonCode.OK).build();
			}
		});
		var invalidEarly = new OracleFacade(invalid).prepareDecision(plus)
			.prepareEarlyFedFeasibility(domains, ignored -> exactShape()).orElseThrow();
		IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
			() -> invalidEarly.fedFeasibility(partial(Arrays.asList(FType.ROW, null), Set.of(0))));
		Assert.assertTrue(failure.getMessage(), failure.getMessage().contains("undeclared ShapeHint"));
	}

	@Test
	public void unresolvedShapeAuthorityPreservesTheExactDiagnosticLeaf() {
		BinaryOp divide = new BinaryOp("full-divide", DataType.MATRIX, ValueType.FP64,
			OpOp2.DIV, matrix("FA", 8, 4), matrix("FB", 8, 4));
		List<List<FType>> domains = List.of(List.of(FType.FULL), List.of(FType.FULL));
		OracleFacade.PreparedDecision prepared = facade().prepareDecision(divide);
		var unresolved = prepared.prepareEarlyFedFeasibility(domains, ignored ->
			new ShapeHint(8, 4, 1000, java.util.Optional.empty(), 8, 4, 8, 4)).orElseThrow();

		Assert.assertEquals("missing fullSinglePartition authority cannot prove FED infeasibility",
			PartialTruth.UNKNOWN, unresolved.fedFeasibility(
				partial(Arrays.asList(FType.FULL, null), Set.of(0))));
		List<List<FType>> retained = new ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(
			domains, FED_REQUIRED, prepared, unresolved, retained::add, null);
		Assert.assertEquals("exact closure needs the unresolved row as a retraction certificate",
			List.of(List.of(FType.FULL, FType.FULL)), retained);
		var evidence = unresolved.evidenceFor(retained.get(0)).orElseThrow();
		Assert.assertEquals(ExecType.CP, evidence.caps().exec());
		Assert.assertEquals(Set.of("fullSinglePartition"),
			evidence.shapeProof().missingRequiredFacts());

		var resolvedFalse = prepared.prepareEarlyFedFeasibility(domains, ignored ->
			new ShapeHint(8, 4, 1000, java.util.Optional.of(false), 8, 4, 8, 4)).orElseThrow();
		Assert.assertEquals("an exact multi-partition proof still permits early rejection",
			PartialTruth.INFEASIBLE, resolvedFalse.fedFeasibility(
				partial(Arrays.asList(FType.FULL, null), Set.of(0))));
	}

	@Test
	public void highArityDependencyProductsFallBackToExactLeavesAtTheVisitBudget() {
		for(String mode : List.of("all-fed", "all-cp", "mixed", "rule-error")) {
			NaryOp operation = highArityOperation(mode);
			OracleFacade.PreparedDecision prepared = highArityFacade(mode).prepareDecision(operation);
			List<List<FType>> domains = new ArrayList<>();
			for(int position = 0; position < operation.getInput().size(); position++)
				domains.add(List.of(FType.ROW, FType.COL));
			var early = prepared.prepareEarlyFedFeasibility(domains,
				ignored -> exactShape()).orElseThrow();
			List<FType> unassignedValues = new ArrayList<>(
				java.util.Collections.nCopies(domains.size(), null));

			PartialTruth prefix = early.fedFeasibility(partial(unassignedValues, Set.of()));
			if(mode.equals("mixed")) {
				Assert.assertEquals(PartialTruth.UNKNOWN, prefix);
				Assert.assertEquals(0, early.diagnostics().budgetExhaustions());
				Assert.assertTrue("mixed evidence must stop before the prefix budget",
					early.diagnostics().completionVisits() < 4096);
			}
			else {
				Assert.assertEquals("an incomplete homogeneous scan cannot prove a prefix", PartialTruth.UNKNOWN,
					prefix);
				Assert.assertEquals(1, early.diagnostics().budgetExhaustions());
				Assert.assertEquals(4096, early.diagnostics().completionVisits());
			}

			List<FType> exact = new ArrayList<>(
				java.util.Collections.nCopies(domains.size(), FType.COL));
			Set<Integer> assigned = new java.util.TreeSet<>();
			for(int position = 0; position < domains.size(); position++)
				assigned.add(position);
			PartialTruth exactTruth = early.fedFeasibility(partial(exact, assigned));
			if(mode.equals("all-fed"))
				Assert.assertEquals(PartialTruth.FEASIBLE, exactTruth);
			else if(mode.equals("all-cp") || mode.equals("mixed"))
				Assert.assertEquals(PartialTruth.INFEASIBLE, exactTruth);
			else
				Assert.assertEquals(PartialTruth.UNKNOWN, exactTruth);
			Assert.assertTrue("complete tuple evidence remains available after prefix fallback",
				early.evidenceFor(exact).isPresent());
			if(mode.equals("rule-error"))
				Assert.assertEquals(ReasonCode.RULE_ERROR,
					early.evidenceFor(exact).orElseThrow().caps().reason());
		}
	}

	private static void assertGeneralParity(Hop hop, List<List<FType>> domains) {
		OracleFacade.PreparedDecision reference = facade().prepareDecision(hop);
		Set<List<FType>> expected = new LinkedHashSet<>();
		PlacementCandidateGenerator.forEachInputCombination(domains, FED_REQUIRED, tuple -> {
			if(reference.decideWithEvidence(tuple, exactShape()).caps().exec() == ExecType.FED)
				expected.add(tuple);
		}, null);

		OracleFacade.PreparedDecision prepared = facade().prepareDecision(hop);
		Assert.assertFalse("fixture must exercise the generic rule-backed path",
			prepared.supportsPartialFedFeasibility());
		var early = prepared.prepareEarlyFedFeasibility(
			privacyFiltered(domains), ignored -> exactShape()).orElseThrow();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<List<FType>> actual = new ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(
			domains, FED_REQUIRED, prepared, early, actual::add, metrics);

		Assert.assertEquals(hop.getOpString(), expected, new LinkedHashSet<>(actual));
		Assert.assertEquals(expected.size(), metrics.snapshot().inputLeaves());
		long privacyMaskedProduct = Math.max(0, domains.get(0).size() - 1);
		for(int position = 1; position < domains.size(); position++)
			privacyMaskedProduct *= domains.get(position).size();
		Assert.assertFalse("fixture must retain at least one legal FED tuple", expected.isEmpty());
		Assert.assertTrue("generic MRV must cut FED-illegal leaves for " + hop.getOpString(),
			expected.size() < privacyMaskedProduct);
		long evaluations = early.diagnostics().oracleEvaluations();
		Assert.assertTrue("dependency-directed Oracle work must not exceed the privacy product",
			evaluations <= privacyMaskedProduct);
		for(List<FType> tuple : actual)
			Assert.assertTrue("surviving tuple must reuse its authoritative evidence",
				early.evidenceFor(tuple).isPresent());
		Assert.assertEquals("evidence lookup must not evaluate the forward rule twice",
			evaluations, early.diagnostics().oracleEvaluations());
		List<List<FType>> repeated = new ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(
			domains, FED_REQUIRED, prepared, early, repeated::add, null);
		Assert.assertEquals("MRV enumeration order must be deterministic", actual, repeated);
	}

	private static List<List<FType>> privacyFiltered(List<List<FType>> domains) {
		List<List<FType>> filtered = new ArrayList<>(domains.size());
		for(int position = 0; position < domains.size(); position++)
			filtered.add(position == 0
				? domains.get(position).stream().filter(java.util.Objects::nonNull).toList()
				: domains.get(position));
		return filtered;
	}

	private static org.apache.sysds.hops.fedplanner.rules.RulesApi.PartialInputs partial(
		List<FType> values, Set<Integer> assigned) {
		return new org.apache.sysds.hops.fedplanner.rules.RulesApi.PartialInputs(values, assigned);
	}

	private static ShapeHint exactShape() {
		return new ShapeHint(8, 4, 1000, java.util.Optional.of(false), 8, 4, 8, 4);
	}

	private static NaryOp highArityOperation(String mode) {
		Hop[] inputs = new Hop[13];
		for(int position = 0; position < inputs.length; position++)
			inputs[position] = matrix(mode + '-' + position, 8, 4);
		return new NaryOp(mode, DataType.MATRIX, ValueType.FP64, OpOpN.MULT, inputs);
	}

	private static OracleFacade highArityFacade(String mode) {
		RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
		registry.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.OTHER; }
			@Override public Set<String> opcodes() { return Set.of(OpOpN.MULT.toString()); }
			@Override public java.util.Optional<DecisionDependencies> decisionDependencies(OpSig sig) {
				Set<Integer> positions = new java.util.TreeSet<>();
				for(int position = 0; position < sig.arity(); position++)
					positions.add(position);
				return java.util.Optional.of(new DecisionDependencies(positions, Set.of(), Set.of()));
			}
			@Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				boolean error = mode.equals("rule-error")
					&& inputs.stream().allMatch(type -> type == FType.COL);
				boolean fed = mode.equals("all-fed")
					|| mode.equals("mixed") && inputs.get(inputs.size() - 1) == FType.ROW;
				var builder = OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
					.exec(fed ? ExecType.FED : ExecType.CP)
					.placement(fed ? FederatedOutput.FOUT : FederatedOutput.LOUT)
					.reason(error ? ReasonCode.RULE_ERROR
						: fed ? ReasonCode.OK : ReasonCode.NO_FED_INPUT);
				if(fed)
					builder.fout(true, FType.ROW);
				return builder.build();
			}
		});
		return new OracleFacade(registry);
	}

	private static OracleFacade facade() {
		return new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
	}

	private static DataOp matrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD,
			name, rows, cols, rows * cols, 1000);
	}
}

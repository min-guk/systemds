/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.Arrays;
import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compare each partial proof with every completion of the unchanged canonical predicate. */
public class JointPartialTruthTest {
	private static final String SOURCES =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));";

	@Test public void correlatedRowsKeepDifferentLegalWorkerPools() throws Exception {
		check(SOURCES + "if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}C=A+B;print(sum(C));");
	}

	@Test public void independentBranchesCannotBorrowCorrelatedOrigins() throws Exception {
		check(SOURCES + "if(p>0.5){A=X;}else{A=Y;}if(q>0.5){B=X;}else{B=Y;}"
			+ "C=A+B;print(sum(C));");
	}

	@Test public void loopSourcesRequireGroundedProof() throws Exception {
		check(SOURCES + "A=X;B=X;i=1;while(i<=2){if(p>0.5){A=X;B=X;}"
			+ "else{A=Y;B=Y;}i=i+1;}C=A+B;print(sum(C));");
	}

	@Test public void loopMatrixScalarKeepsPartialProofsSound() throws Exception {
		check(SOURCES + "A=X;i=1;while(i<=2){if(p>0.5){A=X;}else{A=Y;}"
			+ "i=i+1;}C=p*A;print(sum(C));", true);
	}

	@Test public void functionAliasesPreserveExhaustiveSelectedPoolTruth() throws Exception {
		// Exact CFG alias provenance keeps every legal function-input pool in play until
		// the other reader is selected. Requiring an early FORBIDDEN proof here would
		// restore the old broken-origin behavior; exhaustive truth parity remains required.
		Verification verified = check("f=function(matrix[double] A,matrix[double] B) return (matrix[double] C){"
			+ "i=1;while(i<1){i=i+1;}C=A+B;}" + SOURCES
			+ "C1=f(X,X);C2=f(Y,Y);print(sum(C1)+sum(C2));");
		Assert.assertTrue("exact function aliases must retain legal completions", verified.legalLeaves() > 0);
		Assert.assertEquals("exact function aliases must not invent forbidden completions",
			0, verified.forbiddenLeaves());
	}

	@Test public void aggregateFunctionChoicesCertifyMixedSubtrees() throws Exception {
		Verification verified = check("f=function(matrix[double] A,matrix[double] B) return (matrix[double] C){"
			+ "i=1;while(i<1){i=i+1;}C=A+B;}" + SOURCES
			+ "U=colSums(X);V=colSums(Y);C1=f(U,U);C2=f(V,V);print(sum(C1)+sum(C2));");
		Assert.assertTrue("function choices must certify a subtree before its leaves",
			verified.provenSubtrees() > 0);
		Assert.assertTrue("fixture must retain legal completions", verified.legalLeaves() > 0);
		Assert.assertTrue("fixture must retain forbidden completions", verified.forbiddenLeaves() > 0);
	}

	private record Verification(long provenSubtrees, long legalLeaves, long forbiddenLeaves) { }

	private static Verification check(String script) throws Exception {
		return check(script, false);
	}

	private static Verification check(String script, boolean requireSinglePhysicalInput) throws Exception {
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var model = requireSinglePhysicalInput
			? ExactPhysicalModel.buildWithUnprunedJointFactorsForTest(analysis)
			: ExactPhysicalModel.build(analysis);
		if(requireSinglePhysicalInput) {
			var relations = JointValueMapRelations.from(analysis);
			Assert.assertTrue("fixture must exercise a FED joint consumer with exactly one physical input",
				model.domains().stream().anyMatch(domain -> relations.stream()
					.anyMatch(relation -> relation.consumer() == domain.node().key())
					&& model.hardFactors().stream().anyMatch(factor -> factor.supportsPartialTruth()
						&& factor.scope().get(0).equals(domain.variable()))
					&& domain.alternatives().stream().anyMatch(alternative ->
						alternative.state().execType() == ExecType.FED && alternative.realization() != null
							&& alternative.inputAuthorities().stream().filter(authority ->
								authority.kind() == ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT
									|| authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION)
								.map(ExactPhysicalModel.InputAuthority::inputPosition).distinct().count() == 1)));
		}
		int factors = 0;
		long[] work = new long[4];
		for(var factor : model.hardFactors()) {
			if(!factor.supportsPartialTruth()) continue;
			factors++;
			int[] assignment = new int[factor.scope().size()];
			Arrays.fill(assignment, -1);
			long cells = factor.scope().stream().mapToLong(v -> v.domainSize()).reduce(1, Math::multiplyExact);
			Assert.assertTrue("bounded exhaustive physical fixture: " + cells, cells <= 1_000_000);
			verify(factor, assignment, 0, work);
			var frozen = ExactCategoricalSolver.freezeValidatedFactor(factor);
			Arrays.fill(assignment, 0);
			for(int cell = 0; cell < cells; cell++) {
				Assert.assertEquals(Double.doubleToRawLongBits(factor.cost(assignment)),
					Double.doubleToRawLongBits(frozen.denseCostAt(cell)));
				for(int pos = assignment.length - 1; pos >= 0; pos--) {
					if(++assignment[pos] < factor.scope().get(pos).domainSize()) break;
					assignment[pos] = 0;
				}
			}
		}
		Assert.assertTrue("joint hard factors must retain partial proofs", factors > 0);
		return new Verification(work[1], work[2], work[3]);
	}

	private static int verify(ExactCategoricalSolver.Factor factor, int[] values, int position, long[] work) {
		var proof = factor.partialTruth(values);
		int outcomes = 0;
		if(position == values.length) {
			double cost = factor.cost(values);
			Assert.assertTrue(cost == 0.0 || cost == Double.POSITIVE_INFINITY);
			outcomes = cost == 0.0 ? 1 : 2;
			work[0]++;
			work[cost == 0.0 ? 2 : 3]++;
		}
		else {
			for(int value = 0; value < factor.scope().get(position).domainSize(); value++) {
				values[position] = value;
				outcomes |= verify(factor, values, position + 1, work);
			}
			values[position] = -1;
			if(proof != ExactCategoricalSolver.PartialTruth.UNKNOWN) work[1]++;
		}
		if(proof != ExactCategoricalSolver.PartialTruth.UNKNOWN)
			Assert.assertEquals("invalid proof for " + Arrays.toString(values),
				proof == ExactCategoricalSolver.PartialTruth.ALL_ZERO ? 1 : 2, outcomes);
		return outcomes;
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Locks the exact hard-factor truth tables for a realization-dependent fixture. */
public class ExactPhysicalRealizationSupportFactorCacheTest {
	private static final String EXPECTED_TRUTH_SHA256 =
		"982ec0a922e1d52f88b9520d2663ff3f4c2f8f6dda9ac25837242099004feb01";

	@Test
	public void realizationSupportCachingPreservesEveryHardFactorCell() throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis());
		long directFoutAuthorities = model.domains().stream()
			.flatMap(domain -> domain.alternatives().stream())
			.flatMap(alternative -> alternative.inputAuthorities().stream())
			.filter(authority -> authority.kind() == ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT)
			.count();
		Assert.assertTrue("fixture must exercise graph-owned relocation actions",
			!model.analysis().graph().relocationActions().isEmpty());
		Assert.assertTrue("fixture must exercise direct-FOUT input authority factors",
			directFoutAuthorities > 0);
		long requiredSupports = model.domains().stream()
			.flatMap(domain -> domain.alternatives().stream())
			.filter(alternative -> alternative.supportClause() != null)
			.flatMap(alternative -> alternative.supportClause().requiredInputSupport().stream())
			.count();
		Assert.assertTrue("fixture must exercise realization-support factors", requiredSupports > 0);
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause>
			uniqueClauses = Collections.newSetFromMap(new IdentityHashMap<>());
		long alternatives = 0;
		for(var domain : model.domains())
			for(var alternative : domain.alternatives()) {
				alternatives++;
				if(alternative.supportClause() != null)
					uniqueClauses.add(alternative.supportClause());
			}
		var preparation = model.realizationSupportPreparationStatistics();
		var inputPreparation = model.inputAuthorityPreparationStatistics();
		Assert.assertTrue(preparation.requiredSupportDerivations() > 0);
		Assert.assertTrue(preparation.canonicalReferenceDerivations() > 0);
		Assert.assertTrue(preparation.requiredSupportDerivations() <= uniqueClauses.size());
		Assert.assertTrue(preparation.canonicalReferenceDerivations() <= alternatives);
		Assert.assertTrue(inputPreparation.factorCount() > 0);
		Assert.assertTrue(inputPreparation.consumerAlternativeRows() > 0);
		Assert.assertTrue(inputPreparation.indexedDirectFoutRows() > 0);
		Assert.assertTrue(inputPreparation.canonicalReceiptDerivations() > 0);
		Assert.assertTrue(inputPreparation.canonicalReceiptDerivations() <= alternatives);

		Map<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < model.variables().size(); index++)
			positions.put(model.variables().get(index), index);
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		long cells = 0;
		for(int factorIndex = 0; factorIndex < model.hardFactors().size(); factorIndex++) {
			ExactCategoricalSolver.Factor factor = model.hardFactors().get(factorIndex);
			update(digest, "factor=" + factorIndex + "|scope=");
			for(var variable : factor.scope())
				update(digest, positions.get(variable) + ":" + variable.domainSize() + ",");
			int factorCells = factor.scope().stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
			int[] values = new int[factor.scope().size()];
			for(int cell = 0; cell < factorCells; cell++) {
				int remainder = cell;
				for(int position = values.length - 1; position >= 0; position--) {
					int radix = factor.scope().get(position).domainSize();
					values[position] = remainder % radix;
					remainder /= radix;
				}
				update(digest, Long.toUnsignedString(Double.doubleToRawLongBits(factor.cost(values))) + ",");
			}
			cells += factorCells;
			update(digest, "\n");
		}
		Assert.assertTrue("fixture must enumerate more cells than alternatives", cells >
			model.domains().stream().mapToLong(domain -> domain.alternatives().size()).sum());
		Assert.assertTrue("support derivation must be bounded by identities, not factor cells",
			preparation.requiredSupportDerivations() < cells
				&& preparation.canonicalReferenceDerivations() < cells);
		Assert.assertTrue("input-authority preparation must be bounded by rows, not factor cells",
			inputPreparation.consumerAlternativeRows() < cells
				&& inputPreparation.canonicalReceiptDerivations() < cells);
		Assert.assertEquals(EXPECTED_TRUTH_SHA256, HexFormat.of().formatHex(digest.digest()));
	}

	private static void update(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis analysis()
		throws Exception {
		var program = ProductionShadowFixtureFactory.compile("B-11");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}

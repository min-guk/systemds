/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalPlanSpaceExporterTest {
	@Test
	public void splitVisitorPreservesEveryRawChoiceAndUnknownCoverage() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-21");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		ExactPhysicalModel model = ExactPhysicalModel.build(new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(program));
		BigInteger end = ExactPhysicalPlanSpaceExporter.size(model).min(BigInteger.valueOf(64));
		BigInteger cut = end.divide(BigInteger.TWO);
		List<ExactPhysicalPlanSpaceExporter.Row> whole = new ArrayList<>();
		List<ExactPhysicalPlanSpaceExporter.Row> split = new ArrayList<>();
		List<ExactPhysicalRawSpaceExporter.Row> raw = new ArrayList<>();
		ExactPhysicalPlanSpaceExporter.visit(model, BigInteger.ZERO, end, whole::add);
		ExactPhysicalPlanSpaceExporter.visit(model, BigInteger.ZERO, cut, split::add);
		ExactPhysicalPlanSpaceExporter.visit(model, cut, end, split::add);
		ExactPhysicalRawSpaceExporter.visit(model, BigInteger.ZERO, end, raw::add);
		Assert.assertEquals(whole, split);
		Assert.assertEquals(raw.size(), whole.size());
		Assert.assertTrue(whole.size() > 1);
		for(int rowIndex = 0; rowIndex < whole.size(); rowIndex++) {
			var decoded = whole.get(rowIndex);
			var original = raw.get(rowIndex);
			Assert.assertEquals(original.ordinal(), decoded.ordinal());
			Assert.assertEquals(original.values(), decoded.assignment());
			Assert.assertEquals(original.status(), decoded.modelStatus());
			Assert.assertEquals(original.reason(), decoded.modelReason());
			Assert.assertEquals("UNKNOWN", decoded.globalCoverageStatus());
			Assert.assertEquals(ExactPhysicalPlanSpaceExporter.MISSING_COVERAGE,
				decoded.globalCoverageReason());
			Assert.assertEquals(model.domains().size(), decoded.choices().size());
			for(int index = 0; index < decoded.choices().size(); index++) {
				var choice = decoded.choices().get(index);
				var domain = model.domains().get(index);
				var alternative = domain.alternatives().get(decoded.assignment().get(index));
				Assert.assertEquals(index, choice.domainIndex());
				Assert.assertEquals(decoded.assignment().get(index).intValue(), choice.alternativeIndex());
				Assert.assertEquals(alternative.decision().normalizedSignature(), choice.occurrence());
				Assert.assertEquals(alternative.decision().canonicalSourceOrigin(),
					choice.occurrenceDetails().get("sourceOrigin"));
				Assert.assertEquals(domain.node().kind().name(), choice.nodeKind());
				Assert.assertEquals(domain.node().valueVersion().lexicalVariable(),
					choice.valueVersionDetails().get("lexicalVariable"));
				Assert.assertEquals(model.analysis().compiledInputEdgesInCanonicalOrder().stream()
					.filter(edge -> edge.consumer() == domain.node().key()).count(),
					choice.compiledInputEdges().size());
				Assert.assertEquals(alternative.state().normalizedSignature(), choice.state());
				Assert.assertEquals(alternative.authorityKind().name(), choice.authorityKind());
				Assert.assertEquals(alternative.candidateRule() == null ? null
					: alternative.candidateRule().key().normalizedSignature(), choice.candidateRule());
				Assert.assertEquals(alternative.candidateEmission() == null ? null
					: alternative.candidateEmission().selectionSignature(), choice.candidateEmission());
				Assert.assertEquals(alternative.executionRule() == null ? null
					: alternative.executionRule().key().normalizedSignature(), choice.executionRule());
				Assert.assertEquals(alternative.executionEmission() == null ? null
					: alternative.executionEmission().selectionSignature(), choice.executionEmission());
				Assert.assertEquals(alternative.durableAnchor() == null ? null
					: alternative.durableAnchor().normalizedSignature(), choice.durableAnchor());
				Assert.assertEquals(alternative.orderedInputs().stream()
					.map(input -> input.normalizedSignature()).toList(),
					choice.orderedInputs());
				Assert.assertEquals(alternative.orderedInputs().size(), choice.orderedInputDetails().size());
				for(int position = 0; position < alternative.orderedInputs().size(); position++) {
					Assert.assertEquals(position, choice.orderedInputDetails().get(position).get("position"));
					Assert.assertEquals(alternative.orderedInputs().get(position).presence().name(),
						choice.orderedInputDetails().get(position).get("presence"));
				}
				Assert.assertEquals(alternative.inputAuthorities().size(),
					choice.inputAuthorityDetails().size());
				if(alternative.durableAnchor() != null)
					Assert.assertEquals(alternative.durableAnchor().partitions().size(),
						((java.util.List<?>) choice.durableAnchorDetails().get("partitions")).size());
				Assert.assertEquals(alternative.inputAuthorities().stream()
					.map(ExactPhysicalModel.InputAuthority::signature).toList(), choice.inputAuthorities());
				Assert.assertEquals(alternative.relocationAction() == null ? null
					: alternative.relocationAction().normalizedSignature(), choice.relocationAction());
				Assert.assertEquals(alternative.derivedFoutAction() == null ? null
					: alternative.derivedFoutAction().normalizedSignature(), choice.derivedFoutAction());
				Assert.assertEquals(alternative.realization() == null ? null
					: alternative.realization().key().normalizedSignature(), choice.realization());
				Assert.assertEquals(alternative.supportClause() == null ? null
					: alternative.supportClause().normalizedSignature(), choice.supportClause());
				Assert.assertEquals(original.alternativeSignatures().get(index), choice.alternativeSignature());
			}
		}
	}
}

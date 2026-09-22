/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;

/** Test-only access to the package-private exact physical model; global coverage remains UNKNOWN. */
public final class ExactPhysicalFixtureArtifactBridge {
	private ExactPhysicalFixtureArtifactBridge() { }

	private static ExactPhysicalModel model(String fixture) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return ExactPhysicalModel.build(new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program));
	}

	public static BigInteger size(String fixture) throws Exception {
		return ExactPhysicalPlanSpaceExporter.size(model(fixture));
	}

	public static void stream(String fixture, BigInteger start, BigInteger stop,
		Consumer<Map<String,Object>> sink) throws Exception {
		ExactPhysicalPlanSpaceExporter.visit(model(fixture), start, stop, row -> {
			Map<String,Object> identity = new LinkedHashMap<>();
			identity.put("assignment", row.assignment());
			List<Map<String,Object>> choices = new ArrayList<>();
			for(var choice : row.choices()) {
				Map<String,Object> item = new LinkedHashMap<>();
				item.put("domainIndex", choice.domainIndex());
				item.put("alternativeIndex", choice.alternativeIndex());
				item.put("occurrence", choice.occurrence());
				item.put("state", choice.state());
				item.put("authorityKind", choice.authorityKind());
				item.put("candidateRule", choice.candidateRule());
				item.put("candidateEmission", choice.candidateEmission());
				item.put("executionRule", choice.executionRule());
				item.put("executionEmission", choice.executionEmission());
				item.put("durableAnchor", choice.durableAnchor());
				item.put("relocationAction", choice.relocationAction());
				item.put("derivedFoutAction", choice.derivedFoutAction());
				item.put("orderedInputs", choice.orderedInputs());
				item.put("inputAuthorities", choice.inputAuthorities());
				item.put("realization", choice.realization());
				item.put("supportClause", choice.supportClause());
				item.put("alternativeSignature", choice.alternativeSignature());
				item.put("orderedInputDetails", choice.orderedInputDetails());
				item.put("inputAuthorityDetails", choice.inputAuthorityDetails());
				item.put("durableAnchorDetails", choice.durableAnchorDetails());
				item.put("relocationDetails", choice.relocationDetails());
				item.put("derivedFoutDetails", choice.derivedFoutDetails());
				item.put("occurrenceDetails", choice.occurrenceDetails());
				item.put("realizationDetails", choice.realizationDetails());
				item.put("supportDetails", choice.supportDetails());
				item.put("nodeKind", choice.nodeKind());
				item.put("valueVersionDetails", choice.valueVersionDetails());
				item.put("opcode", choice.opcode());
				item.put("compiledInputEdges", choice.compiledInputEdges());
				choices.add(item);
			}
			identity.put("choices", choices);
			identity.put("modelStatus", row.modelStatus().name());
			identity.put("modelReason", row.modelReason());
			identity.put("globalCoverageStatus", row.globalCoverageStatus());
			identity.put("globalCoverageReason", row.globalCoverageReason());
			Map<String,Object> result = new LinkedHashMap<>();
			result.put("ordinal", row.ordinal().toString());
			result.put("identity", identity);
			sink.accept(result);
		});
	}
}

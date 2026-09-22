/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License.
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalFixtureArtifactBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.junit.Assert;
import org.junit.Test;

/** Same-version native-coordinate cross-check; it is not a full physical-plan decoder. */
public class CurrentPeNativeCorrespondenceTest {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	@Test
	public void b01NativeChoiceSetsAgree() throws Exception { compare("B-01", 1); }

	@Test
	public void b21NativeChoiceSetsAgreeAfterExactPCompression() throws Exception { compare("B-21", 192); }

	@Test
	public void representativeFederatedAndActionFixturesAgree() throws Exception {
		for(String fixture : List.of("B-11", "B-14", "B-22")) compare(fixture, -1);
	}

	@Test
	public void allProtectedFixturesHaveNativeChoiceCorrespondence() throws Exception {
		for(String fixture : ProductionShadowFixtureFactory.ids()) {
			if("B-13".equals(fixture)) continue; // expected native privacy build rejection
			try { compare(fixture, -1); }
			catch(Exception error) { throw new IllegalStateException("Native comparison failed at " + fixture, error); }
		}
	}

	@Test
	public void b13ProtectedInputIsRejectedByBothNativeModelBuilders() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-13");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		DMLRuntimeException pError = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(pError.getMessage().contains("No privacy-safe physical placement"));
		DMLRuntimeException eError = Assert.assertThrows(DMLRuntimeException.class,
			() -> ExactPhysicalFixtureArtifactBridge.size("B-13"));
		Assert.assertTrue(eError.getMessage().contains("No privacy-safe physical placement"));
	}

	private void compare(String fixture, int expected) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		ClosedPlanRelationEnumerator p = new ClosedPlanRelationEnumerator(analysis);
		Set<String> pKeys = new HashSet<>();
		Map<String,Set<String>> pActions = new HashMap<>();
		var summary = p.enumerateStates(BigInteger.ZERO, p.stateCount(), audit -> {
			String key = key(pChoiceRows(analysis, audit));
			pKeys.add(key);
			Set<String> actions = new HashSet<>();
			RelocationSelections.emittedActions(analysis, analysis.graph().relocationActions(),
				audit.assignment(), audit.candidates(), audit.relocations())
				.forEach(action -> actions.add(action.normalizedSignature()));
			audit.derivedFoutActions().forEach(action -> actions.add(action.normalizedSignature()));
			pActions.put(key, actions);
		});
		if(expected >= 0) Assert.assertEquals(expected, summary.accepted().intValueExact());
		Assert.assertEquals(BigInteger.ZERO, summary.unknown());
		if(expected >= 0) Assert.assertEquals(expected, pKeys.size());

		Set<String> eKeys = new HashSet<>();
		Map<String,Set<String>> eActions = new HashMap<>();
		BigInteger size = ExactPhysicalFixtureArtifactBridge.size(fixture);
		ExactPhysicalFixtureArtifactBridge.stream(fixture, BigInteger.ZERO, size, raw -> {
			Map<?,?> identity = (Map<?,?>) raw.get("identity");
			String status = (String) identity.get("modelStatus");
			if("UNKNOWN".equals(status))
				throw new AssertionError("Exact hard-factor classification incomplete: " + raw.get("ordinal"));
			if("EMITTED".equals(status)) {
				@SuppressWarnings("unchecked")
				List<Map<String,Object>> choices = (List<Map<String,Object>>) identity.get("choices");
				String key = key(eChoiceRows(choices));
				eKeys.add(key);
				Set<String> actions = new HashSet<>();
				for(Map<String,Object> choice : choices) {
					@SuppressWarnings("unchecked")
					List<Map<String,Object>> authorities =
						(List<Map<String,Object>>) choice.get("inputAuthorityDetails");
					for(Map<String,Object> authority : authorities)
						if("RELOCATION".equals(authority.get("kind"))) {
							Object action = authority.get("relocationActionKey");
							if(action == null) throw new IllegalStateException("Relocation key missing");
							actions.add((String) action);
						}
					for(String name : List.of("relocationAction", "derivedFoutAction"))
						if(choice.get(name) != null) actions.add((String) choice.get(name));
				}
				eActions.put(key, actions);
			}
		});
		if(expected >= 0) Assert.assertEquals(expected, eKeys.size());
		Set<String> pOnly = new HashSet<>(pKeys);
		pOnly.removeAll(eKeys);
		Set<String> eOnly = new HashSet<>(eKeys);
		eOnly.removeAll(pKeys);
		Assert.assertTrue(fixture + " P-only native choice count: " + pOnly.size(), pOnly.isEmpty());
		Assert.assertTrue(fixture + " E-only native choice count: " + eOnly.size(), eOnly.isEmpty());
		for(String key : pKeys) {
			Set<String> left = pActions.get(key);
			Set<String> right = eActions.get(key);
			Assert.assertNotNull("Exact action reference missing for native choice", right);
			if(!left.equals(right)) {
				Set<String> leftOnly = new HashSet<>(left);
				leftOnly.removeAll(right);
				Set<String> rightOnly = new HashSet<>(right);
				rightOnly.removeAll(left);
				Assert.fail("Native action references disagree: P=" + left.size()
					+ " E=" + right.size() + " P-only=" + leftOnly.size()
					+ " E-only=" + rightOnly.size() + " P-first="
					+ leftOnly.stream().findFirst().map(value -> value.substring(0, Math.min(95, value.length())))
						.orElse("") + " E-first=" + rightOnly.stream().findFirst()
						.map(value -> value.substring(0, Math.min(95, value.length()))).orElse(""));
			}
		}
	}

	private static String key(List<Map<String,Object>> rows) {
		try { return JSON.writeValueAsString(rows); }
		catch(Exception ex) { throw new IllegalStateException(ex); }
	}

	private static List<Map<String,Object>> pChoiceRows(PlacementAnalysis analysis,
		FullProductionJointPlanExport.Audit audit) {
		Map<String,CandidateSelectionReceipt> selected = new HashMap<>();
		for(CandidateSelectionReceipt receipt : audit.candidates()) {
			String owner = receipt.rule().parentOccurrence().normalizedSignature();
			if(selected.put(owner, receipt) != null)
				throw new IllegalStateException("Two candidate receipts for one owner");
		}
		List<Map<String,Object>> rows = new ArrayList<>();
		for(var entry : audit.assignment().entrySet()) {
			String occurrence = entry.getKey().normalizedSignature();
			CandidateSelectionReceipt receipt = selected.remove(occurrence);
			NeutralPlacementGraph.Node node = analysis.graph().node(entry.getKey()).orElseThrow();
			rows.add(choice(occurrence, entry.getValue().normalizedSignature(), node.kind().name(),
				receipt == null ? "SYNTHETIC_BOUNDARY" : "CAPTURED_RULE",
				receipt == null ? null : receipt.rule().normalizedSignature(),
				receipt == null ? null : receipt.emission().selectionSignature(),
				receipt == null ? null : receipt.realization().key().normalizedSignature(),
				receipt == null ? null : receipt.supportClause().normalizedSignature(),
				receipt == null ? List.of() : receipt.rule().orderedInputs().stream()
					.map(input -> input.normalizedSignature()).toList()));
		}
		if(!selected.isEmpty())
			throw new IllegalStateException("Candidate receipt owner absent from placement assignment");
		rows.sort((left, right) -> ((String)left.get("occurrence")).compareTo((String)right.get("occurrence")));
		return rows;
	}

	private static List<Map<String,Object>> eChoiceRows(List<Map<String,Object>> choices) {
		List<Map<String,Object>> rows = new ArrayList<>();
		for(Map<String,Object> choice : choices) {
			if(choice.get("executionRule") != null || choice.get("executionEmission") != null)
				throw new IllegalStateException("Unsupported native execution choice in projected correspondence");
			rows.add(choice((String) choice.get("occurrence"), (String) choice.get("state"),
				(String) choice.get("nodeKind"), (String) choice.get("authorityKind"),
				(String) choice.get("candidateRule"), (String) choice.get("candidateEmission"),
				(String) choice.get("realization"), (String) choice.get("supportClause"),
				castStrings(choice.get("orderedInputs"))));
		}
		rows.sort((left, right) -> ((String)left.get("occurrence")).compareTo((String)right.get("occurrence")));
		return rows;
	}

	@SuppressWarnings("unchecked")
	private static List<String> castStrings(Object value) { return (List<String>) value; }

	private static Map<String,Object> choice(String occurrence, String state, String kind,
		String authority, String rule, String emission, String realization, String support,
		List<String> orderedInputs) {
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("occurrence", occurrence);
		row.put("state", state);
		row.put("kind", kind);
		row.put("authority", authority);
		row.put("candidateRule", rule);
		row.put("candidateEmission", emission);
		row.put("realization", realization);
		row.put("support", support);
		row.put("orderedInputs", orderedInputs);
		return Collections.unmodifiableMap(row);
	}
}

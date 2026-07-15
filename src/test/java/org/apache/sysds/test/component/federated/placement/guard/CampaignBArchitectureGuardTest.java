/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Executable all-four ownership closure; delegation cannot hide a second semantic universe. */
public class CampaignBArchitectureGuardTest {
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Map<String,String> ROOTS = Map.of(
		"FED_ALL", "org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll",
		"HEURISTIC", "org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic",
		"DP", "org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased",
		"MIN_ST", "org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCut");
	private static final Map<String,String> ADAPTERS = Map.of("FED_ALL", "FedAllPlacementAdapter",
		"HEURISTIC", "HeuristicPlacementAdapter", "DP", "DpPlacementAdapter", "MIN_ST", "MinStPlacementAdapter");

	@Test public void allFourOwnershipClosuresHaveOneSharedAnalysisBoundaryAndNoHiddenUniverse() throws Exception {
		Map<String,CampaignBPlannerOwnershipClosure.Unit> index = CampaignBPlannerOwnershipClosure.index(
			ROOT.resolve("src/main/java/org/apache/sysds/hops/fedplanner"));
		List<String> violations = new ArrayList<>();
		for(String planner : new java.util.TreeSet<>(ROOTS.keySet())) {
			List<CampaignBPlannerOwnershipClosure.Unit> closure =
				CampaignBPlannerOwnershipClosure.closure(ROOTS.get(planner), index);
			try { CampaignBPlannerOwnershipClosure.assertPositiveAdapterBoundary(closure, ADAPTERS.get(planner)); }
			catch(AssertionError e) { violations.add(planner + '|' + e.getMessage()); }
			violations.addAll(CampaignBPlannerOwnershipClosure.violations(closure));
		}
		violations.sort(String::compareTo);
		Assert.assertEquals("R4_OWNERSHIP_CLOSURE", List.of(), violations);
	}

	@Test public void delegationMultilineRenamingAndInjectedCollaboratorsCannotEvadeClosure() throws Exception {
		Path dir = Files.createTempDirectory("r4-owner");
		Path pkg = dir.resolve("org/apache/sysds/hops/fedplanner/demo"); Files.createDirectories(pkg);
		Files.writeString(pkg.resolve("Root.java"), "package org.apache.sysds.hops.fedplanner.demo; class Root { final Helper renamed; Root(Helper x){renamed=x;} void run(){renamed.go();}}\n");
		Files.writeString(pkg.resolve("Helper.java"), "package org.apache.sysds.hops.fedplanner.demo; class Helper { void go(){ new\n OracleFacade(); } }\n");
		Files.writeString(pkg.resolve("OracleFacade.java"), "package org.apache.sysds.hops.fedplanner.demo; class OracleFacade {}\n");
		Map<String,CampaignBPlannerOwnershipClosure.Unit> index = CampaignBPlannerOwnershipClosure.index(dir);
		List<CampaignBPlannerOwnershipClosure.Unit> closure = CampaignBPlannerOwnershipClosure.closure(
			"org.apache.sysds.hops.fedplanner.demo.Root", index);
		Assert.assertEquals(3, closure.size());
		Assert.assertTrue(CampaignBPlannerOwnershipClosure.violations(closure).stream()
			.anyMatch(v -> v.contains("oraclefacade")));
	}

	@Test public void scannerIgnoresLiteralsButFindsExecutableMultilineAndNestedTokens() {
		String source = "// OracleFacade\nString x=\"RulesCore\"; String t=\"\"\"fallback\"\"\";"
			+ " class Outer { class Inner { void x(){ new\\n FederatedPlanMinSTGraph(); } } }";
		List<String> ids = JavaSourceTokenScanner.identifiers(source);
		Assert.assertFalse(ids.contains("OracleFacade")); Assert.assertFalse(ids.contains("RulesCore"));
		Assert.assertFalse(ids.contains("fallback")); Assert.assertTrue(ids.contains("FederatedPlanMinSTGraph"));
	}

	@Test public void closureIsFilesystemOrderIndependent() throws Exception {
		Path dir = Files.createTempDirectory("r4-order"), pkg = dir.resolve("org/apache/sysds/hops/fedplanner/order");
		Files.createDirectories(pkg);
		Files.writeString(pkg.resolve("Zed.java"), "package org.apache.sysds.hops.fedplanner.order; class Zed{}\n");
		Files.writeString(pkg.resolve("Root.java"), "package org.apache.sysds.hops.fedplanner.order; class Root{ Zed z;}\n");
		Map<String,CampaignBPlannerOwnershipClosure.Unit> index = CampaignBPlannerOwnershipClosure.index(dir);
		List<String> one = CampaignBPlannerOwnershipClosure.closure("org.apache.sysds.hops.fedplanner.order.Root", index)
			.stream().map(CampaignBPlannerOwnershipClosure.Unit::fqcn).toList();
		Map<String,CampaignBPlannerOwnershipClosure.Unit> reversed = new LinkedHashMap<>();
		java.util.ArrayList<String> keys = new java.util.ArrayList<>(index.keySet());
		java.util.Collections.reverse(keys); keys.forEach(k -> reversed.put(k, index.get(k)));
		List<String> two = CampaignBPlannerOwnershipClosure.closure("org.apache.sysds.hops.fedplanner.order.Root", reversed)
			.stream().map(CampaignBPlannerOwnershipClosure.Unit::fqcn).toList();
		Assert.assertEquals(one, two);
	}
}

package org.apache.sysds.test.component.federated.placement.shadow;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.junit.Assert;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;

/** RED contract for explicit B09 origin/clone recompile provenance. */
public class CampaignBB09ExplicitRecompileFixtureContractTest {
	@Test public void b09CarriesExplicitCloneRecompileFacts() throws Exception {
		NeutralPlacementGraph b09 = new NeutralPlacementGraphBuilder().build(ProductionShadowFixtureFactory.compile("B-09"));
		var clones = b09.nodes().stream().filter(n -> n.kind() == NeutralPlacementGraph.NodeKind.CLONE
			&& n.valueVersion().versionKind().name().equals("CLONE_RECOMPILE")).toList();
		Assert.assertEquals(1, clones.size());
		var clone = clones.get(0);
		Assert.assertNotEquals("compiled", clone.key().recompileContext().toLowerCase());
		var origins = b09.nodes().stream().filter(n -> n.kind() != NeutralPlacementGraph.NodeKind.CLONE
			&& n.key().canonicalSourceOrigin().equals(clone.key().canonicalSourceOrigin())).toList();
		Assert.assertEquals(1, origins.size());
		var origin = origins.get(0);
		Assert.assertEquals(origin.key().canonicalSourceOrigin(), clone.key().canonicalSourceOrigin());
		var same = b09.constraints().stream().filter(c -> c.kind() == NeutralPlacementGraph.ConstraintKind.SAME_ORIGIN
			&& ((c.left().equals(origin.key()) && c.right().equals(clone.key()))
				|| (c.right().equals(origin.key()) && c.left().equals(clone.key())))).toList();
		Assert.assertEquals(1, same.size());
		Assert.assertFalse(clone.valueVersion().predecessorVersions().isEmpty());
		Assert.assertTrue(clone.valueVersion().predecessorVersions().stream().allMatch(p -> p.startsWith("input-")));
		NeutralPlacementGraph repeated = new NeutralPlacementGraphBuilder()
			.build(ProductionShadowFixtureFactory.compile("B-09"));
		var repeatedClone = repeated.nodes().stream()
			.filter(n -> n.kind() == NeutralPlacementGraph.NodeKind.CLONE).findFirst().orElseThrow();
		Assert.assertEquals(clone.valueVersion().predecessorVersions(),
			repeatedClone.valueVersion().predecessorVersions());
	}
	@Test public void cloneExclusionIsTheExpectedRedBoundary() throws Exception {
		NeutralPlacementGraph b09 = new NeutralPlacementGraphBuilder().build(ProductionShadowFixtureFactory.compile("B-09"));
		var clone = b09.nodes().stream().filter(n -> n.kind() == NeutralPlacementGraph.NodeKind.CLONE)
			.findFirst().orElseThrow();
		Assert.assertEquals("recompile", clone.key().recompileContext());
		Assert.assertTrue(clone.exclusions().stream()
			.anyMatch(x -> "RECOMPILE_CP_FOUT".equals(x.reasonCode().name())));
	}
	@Test public void b05RemainsLoopOnly() throws Exception {
		NeutralPlacementGraph b05 = new NeutralPlacementGraphBuilder().build(ProductionShadowFixtureFactory.compile("B-05"));
		Assert.assertTrue(b05.nodes().stream().noneMatch(n -> n.kind() == NeutralPlacementGraph.NodeKind.CLONE
			|| n.valueVersion().versionKind().name().equals("CLONE_RECOMPILE")));
		Assert.assertTrue(b05.nodes().stream().flatMap(n -> n.exclusions().stream())
			.noneMatch(x -> "RECOMPILE_CP_FOUT".equals(x.reasonCode().name())));
	}
	@Test public void genericClassificationAndSourceScan() throws Exception {
		NeutralPlacementGraph b09 = new NeutralPlacementGraphBuilder().build(ProductionShadowFixtureFactory.compile("B-09"));
		NeutralPlacementGraph b05 = new NeutralPlacementGraphBuilder().build(ProductionShadowFixtureFactory.compile("B-05"));
		Assert.assertEquals("ACTUAL_ALL_LOCAL/recompile unsupported", classify(b09));
		Assert.assertEquals("ACTUAL_ALL_LOCAL_LOOP", classify(b05));
		String all = Files.readString(Path.of("src/test/java/org/apache/sysds/test/component/federated/placement/shadow/CampaignBB09ExplicitRecompileFixtureContractTest.java"));
		int start = all.lastIndexOf("private static String classify"); int depth = 0; int end = -1;
		for(int i = all.indexOf('{', start); i < all.length(); i++) { if(all.charAt(i)=='{') depth++; else if(all.charAt(i)=='}' && --depth==0) { end=i+1; break; } }
		String source = all.substring(start, end);
		for(String forbidden : new String[]{"fixtureId", "rowId", "literal", "fingerprint", "HopID", "worker", "shape", "manifest", "Expected"})
			Assert.assertFalse("forbidden classifier token: "+forbidden, source.substring(source.indexOf("private static String classify")).contains(forbidden));
	}
	private static String classify(NeutralPlacementGraph graph) {
		boolean clone = graph.nodes().stream().anyMatch(n -> n.kind() == NeutralPlacementGraph.NodeKind.CLONE
			&& n.valueVersion().versionKind().name().equals("CLONE_RECOMPILE"));
		return clone ? "ACTUAL_ALL_LOCAL/recompile unsupported" : "ACTUAL_ALL_LOCAL_LOOP";
	}
	@Test public void repeatedCompilationIsStableAndClassifierIsStructural() throws Exception {
		String a = new NeutralPlacementGraphBuilder().build(ProductionShadowFixtureFactory.compile("B-09")).normalizedSignature();
		String b = new NeutralPlacementGraphBuilder().build(ProductionShadowFixtureFactory.compile("B-09")).normalizedSignature();
		Assert.assertEquals(a, b);
		Assert.assertFalse(a.contains("fixtureId") || a.contains("manifest") || a.contains("Expected"));
	}
}

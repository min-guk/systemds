/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ExactRelocationObligationIndexTest {
	private static final PlacementState FED_ROW = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);

	@Test
	public void exactMixedEqualityLookupPreservesEveryCanonicalAction() {
		CompiledHopKey consumer = key("consumer");
		CompiledHopKey equalForeignConsumer = key("consumer");
		Assert.assertEquals(consumer, equalForeignConsumer);
		Assert.assertNotSame(consumer, equalForeignConsumer);
		ValueVersionKey source = version("source");
		RelocationAction first = action("a", source, consumer, 2, FED_ROW,
			List.of("call-a", "call-b"));
		RelocationAction second = action("b", source, consumer, 2, FED_ROW,
			List.of("call-c"));
		RelocationAction unrelated = action("c", source, consumer, 3, FED_ROW,
			List.of("call-d"));
		List<RelocationAction> canonical = new ArrayList<>(List.of(second, unrelated, first));
		canonical.sort(null);
		var index = ExactPhysicalModel.RelocationObligationIndex.build(canonical);

		List<RelocationAction> expected = canonical.stream()
			.filter(action -> action == first || action == second).toList();
		List<RelocationAction> actual = index.actions(new ValueVersionKey(source.programFingerprint(),
			source.lexicalVariable(), source.definingControlRegion(), source.definitionOrdinal(),
			source.versionKind(), source.predecessorVersions()), FType.ROW, consumer, 2,
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false));
		Assert.assertEquals(expected.size(), actual.size());
		for(int ordinal = 0; ordinal < expected.size(); ordinal++)
			Assert.assertSame(expected.get(ordinal), actual.get(ordinal));
		Assert.assertTrue("multiple matching obligations must not duplicate one action",
			actual.stream().filter(action -> action == first).count() == 1);
		Assert.assertTrue("record-equal foreign consumer identity must not match",
			index.actions(source, FType.ROW, equalForeignConsumer, 2, FED_ROW).isEmpty());
		Assert.assertTrue(index.actions(source, FType.COL, consumer, 2, FED_ROW).isEmpty());
		Assert.assertTrue("legacy null FType comparison is an exact miss, not an error",
			index.actions(source, null, consumer, 2, FED_ROW).isEmpty());
		Assert.assertTrue(index.actions(source, FType.ROW, consumer, 4, FED_ROW).isEmpty());
		Assert.assertTrue(index.actions(version("other"), FType.ROW, consumer, 2, FED_ROW).isEmpty());
		Assert.assertTrue(index.actions(source, FType.ROW, consumer, 2,
			new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false)).isEmpty());
	}

	@Test
	public void indexedBuildPreservesLegacyDomainsAuthoritiesAndRawFactorTruth() throws Exception {
		PlacementAnalysis analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		ExactPhysicalModel indexed = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel legacy = ExactPhysicalModel
			.buildWithLegacyRelocationActionScanForTest(analysis);
		assertModelParity(legacy, indexed);

		var work = indexed.relocationObligationIndexStatistics();
		Assert.assertTrue("fixture must perform repeated exact obligation lookups", work.lookups() > 1);
		Assert.assertTrue("fixture must own relocation actions", work.actionCount() > 0);
		Assert.assertTrue("indexed construction must replace repeated full inventory visits: " + work,
			work.legacyFullScanActionVisits() > work.indexConstructionEntriesVisited());
		System.out.println("EXACT_RELOCATION_INDEX_WORK|" + work);
	}

	@Test
	public void unprunedAuthorityProductsAlsoPreserveLegacyFullScan() throws Exception {
		PlacementAnalysis analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		ExactPhysicalModel indexed = ExactPhysicalModel
			.buildWithLegacyInputAuthorityProductsForTest(analysis);
		ExactPhysicalModel legacy = ExactPhysicalModel
			.buildWithLegacyRelocationActionScanForTest(analysis, false);
		assertModelParity(legacy, indexed);
	}

	private static void assertModelParity(ExactPhysicalModel legacy, ExactPhysicalModel indexed) {
		Assert.assertEquals(legacy.domains().size(), indexed.domains().size());
		for(int domain = 0; domain < indexed.domains().size(); domain++) {
			var expected = legacy.domains().get(domain);
			var actual = indexed.domains().get(domain);
			Assert.assertSame(expected.node(), actual.node());
			Assert.assertEquals(expected.alternatives().size(), actual.alternatives().size());
			for(int value = 0; value < actual.alternatives().size(); value++) {
				var expectedAlternative = expected.alternatives().get(value);
				var actualAlternative = actual.alternatives().get(value);
				Assert.assertEquals(expectedAlternative.signature(), actualAlternative.signature());
				Assert.assertSame(expectedAlternative.relocationAction(), actualAlternative.relocationAction());
				Assert.assertEquals(expectedAlternative.inputAuthorities().size(),
					actualAlternative.inputAuthorities().size());
				for(int input = 0; input < actualAlternative.inputAuthorities().size(); input++)
					Assert.assertSame(expectedAlternative.inputAuthorities().get(input).relocationAction(),
						actualAlternative.inputAuthorities().get(input).relocationAction());
			}
		}
		assertRawFactorParity(legacy, indexed);
	}

	private static void assertRawFactorParity(ExactPhysicalModel expected,
		ExactPhysicalModel actual) {
		Assert.assertEquals(expected.hardFactors().size(), actual.hardFactors().size());
		for(int index = 0; index < actual.hardFactors().size(); index++) {
			var left = expected.hardFactors().get(index);
			var right = actual.hardFactors().get(index);
			Assert.assertEquals(left.scope().stream().map(variable -> variable.key() + ':'
				+ variable.domainSize()).toList(), right.scope().stream().map(variable ->
				variable.key() + ':' + variable.domainSize()).toList());
			long cells = left.scope().stream().mapToLong(variable -> variable.domainSize())
				.reduce(1L, Math::multiplyExact);
			Assert.assertTrue("focused fixture factor unexpectedly too large", cells <= 1_000_000);
			int[] values = new int[left.scope().size()];
			for(long cell = 0; cell < cells; cell++) {
				decode(left, cell, values);
				Assert.assertEquals("factor=" + index + ",cell=" + cell,
					Double.doubleToRawLongBits(left.cost(values)),
					Double.doubleToRawLongBits(right.cost(values)));
			}
		}
	}

	private static void decode(ExactCategoricalSolver.Factor factor, long cell, int[] values) {
		for(int position = values.length - 1; position >= 0; position--) {
			int size = factor.scope().get(position).domainSize();
			values[position] = (int) (cell % size);
			cell /= size;
		}
	}

	private static RelocationAction action(String id, ValueVersionKey source,
		CompiledHopKey consumer, int inputPosition, PlacementState required,
		List<String> contexts) {
		RelocationActionKey key = new RelocationActionKey(source, required,
			anchor("anchor-" + id, "worker-" + id + ":1234"), "scope-" + id,
			List.of(consumer));
		List<ObligationKey> obligations = contexts.stream().map(context -> new ObligationKey(
			consumer, inputPosition, source, required, key, context)).toList();
		return new RelocationAction(key, obligations);
	}

	private static DurableAnchorKey anchor(String id, String worker) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("index", "main", "main", "compiled", region(), id, id);
	}

	private static ValueVersionKey version(String id) {
		return new ValueVersionKey("index", id, region(), 0, VersionKind.ORDINARY, List.of());
	}

	private static ControlRegionKey region() {
		return new ControlRegionKey("index", "main", List.of("main/0"), "main", "compiled");
	}
}

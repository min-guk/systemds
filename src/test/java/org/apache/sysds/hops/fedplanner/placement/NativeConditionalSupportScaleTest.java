/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Construction counts at scale; small-domain suites separately prove exact member parity. */
public class NativeConditionalSupportScaleTest {
	@Test(timeout = 20000)
	public void hundredThousandMemberComplementConstructsOnlyRequestedClauses() {
		var ordinary = NativeContinuitySupportFixtureBridge.realization("conditional-scale", 512, 512);
		var ordinaryClauses = (NativeContinuitySupportClauses)ordinary.supportClauses();
		var base = ordinaryClauses.product();
		var axes = base.axes();
		var excluded = List.of(axes.get(0).subList(0, 256), axes.get(1).subList(0, 256));
		var owner = NativeContinuitySupportFixtureBridge.key("conditional-scale-proof-owner");
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			var relation = NativeContinuitySupportClauses.exactComplement(owner, base, excluded,
				base.outputWorkerPoolWitness(), true);
			Assert.assertNotNull(relation);
			Assert.assertEquals(196_608, relation.size());
			int firstHash = relation.hashCode();
			Assert.assertEquals(firstHash, relation.hashCode());
			Assert.assertEquals(0, relation.materializedHandleCount());
			Assert.assertEquals("factory and complete-list hashing must not construct temporary clauses",
				0, metrics.objectCreationSnapshot().explicitSupportClauses());
			Assert.assertEquals(-1, relation.product().ordinalOfExactAuthorityBindings(
				List.of(excluded.get(0).get(0), excluded.get(1).get(0))));
			for(int ordinal : new int[] {0, relation.size() / 2, relation.size() - 1}) {
				var bindings = relation.product().bindingsAt(ordinal);
				Assert.assertEquals(ordinal, relation.product().ordinalOfExactAuthorityBindings(bindings));
				Assert.assertFalse(excluded.get(0).contains(bindings.get(0))
					&& excluded.get(1).contains(bindings.get(1)));
				var clause = relation.get(ordinal);
				Assert.assertSame(clause, relation.get(ordinal));
				Assert.assertSame(owner, clause.proofDependencies().get(0).owner());
				Assert.assertEquals(ordinal, relation.ordinalOfExactAuthorityClause(clause));
			}
			Assert.assertEquals(3, relation.materializedHandleCount());
			Assert.assertEquals(3, metrics.objectCreationSnapshot().explicitSupportClauses());
			Assert.assertEquals(0, metrics.objectCreationSnapshot().indexedSupportHandles());
			Assert.assertEquals(0, ordinaryClauses.materializedHandleCount());
			System.out.println("CONDITIONAL_NATIVE_SCALE|logicalMembers=" + relation.size()
				+ "|explicitClauses=" + metrics.objectCreationSnapshot().explicitSupportClauses()
				+ "|requestedMembers=3");
		}
		finally { PlacementIdentity.endAnalysisScope(); }
	}
}

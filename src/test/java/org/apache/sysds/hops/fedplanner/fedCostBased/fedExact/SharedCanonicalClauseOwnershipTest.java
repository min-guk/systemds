/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.AuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.SearchSpaceMetrics;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class SharedCanonicalClauseOwnershipTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementEmissionState LOCAL_EMISSION =
		new PlacementEmissionState(LOCAL, false);
	private static final CompiledHopKey OWNER = key("owner");
	private static final CandidateRuleKey RULE = new CandidateRuleKey(OWNER, List.of());

	@Test
	public void largeSharedListBuildsOneLazyIdentityIndexAndRetainsFirstOrdinal() throws Exception {
		List<Object> identities = new ArrayList<>();
		for(int index = 0; index < 32; index++)
			identities.add(new Object());
		identities.set(17, identities.get(3));
		CountedList<Object> backing = new CountedList<>(identities);
		List<?> shared = shared(backing);

		Assert.assertEquals(3, firstIdentityOrdinal(shared, identities.get(3)));
		int coldGets = backing.getCalls;
		Assert.assertTrue("cold index construction must visit the complete large list",
			coldGets >= backing.size());
		Assert.assertEquals(31, firstIdentityOrdinal(shared, identities.get(31)));
		Assert.assertEquals(-1, firstIdentityOrdinal(shared, new Object()));
		Assert.assertEquals("warm lookups must not rescan the backing list", coldGets, backing.getCalls);
	}

	@Test
	public void subsetOwnsOnlyItsRelativeFirstMiddleAndLastIdentities() throws Exception {
		List<Object> values = new ArrayList<>();
		for(int index = 0; index < 40; index++)
			values.add(new Object());
		List<?> subset = shared(values).subList(5, 35);
		Assert.assertEquals(0, firstIdentityOrdinal(subset, values.get(5)));
		Assert.assertEquals(15, firstIdentityOrdinal(subset, values.get(20)));
		Assert.assertEquals(29, firstIdentityOrdinal(subset, values.get(34)));
		Assert.assertEquals(-1, firstIdentityOrdinal(subset, values.get(4)));
		Assert.assertEquals(-1, firstIdentityOrdinal(subset, values.get(35)));
	}

	@Test
	public void concurrentColdPublicationAlwaysReturnsExactIdentityOrdinals() throws Exception {
		List<Object> values = new ArrayList<>();
		for(int index = 0; index < 64; index++)
			values.add(new Object());
		List<?> shared = shared(values);
		var pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Boolean>> queries = new ArrayList<>();
			for(int thread = 0; thread < 8; thread++)
				queries.add(() -> {
					for(int repeat = 0; repeat < 100; repeat++)
						if(firstIdentityOrdinal(shared, values.get(repeat % values.size()))
							!= repeat % values.size())
							return false;
					return firstIdentityOrdinal(shared, new Object()) == -1;
				});
			for(var result : pool.invokeAll(queries))
				Assert.assertTrue(result.get());
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void smallSharedAndOrdinaryListsRetainLinearOwnershipSemantics() throws Exception {
		Object first = new Object(), last = new Object();
		CountedList<Object> backing = new CountedList<>(List.of(first, new Object(), last));
		List<?> shared = shared(backing);
		Assert.assertEquals(2, firstIdentityOrdinal(shared, last));
		int firstScan = backing.getCalls;
		Assert.assertEquals(2, firstIdentityOrdinal(shared, last));
		Assert.assertTrue("small lists deliberately retain the allocation-free linear scan",
			backing.getCalls > firstScan);

		CandidateRealizationSupportClause only = clause(100);
		CandidateEmissionRealization singleton = new CandidateEmissionRealization(
			PlacementRealizationKey.local(LOCAL_EMISSION), List.of(only));
		Assert.assertFalse("ordinary singleton list must not be replaced solely for indexing",
			singleton.supportClauses().getClass().getName().contains("SharedCanonicalList"));
		Assert.assertTrue(owns(singleton, only));
	}

	@Test
	public void receiptAndAlternativeAcceptOwnedClausesAndRejectEqualForeignIdentity() throws Exception {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(int index = 0; index < 32; index++)
			clauses.add(clause(index));
		@SuppressWarnings("unchecked")
		List<CandidateRealizationSupportClause> shared =
			(List<CandidateRealizationSupportClause>)shared(clauses);
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementRealizationKey.local(LOCAL_EMISSION), shared);
		CandidateEmissionFact emission = new CandidateEmissionFact(
			LOCAL_EMISSION, null, null, List.of(realization));

		for(int ordinal : new int[] {0, 16, 31}) {
			CandidateRealizationSupportClause owned = realization.supportClauses().get(ordinal);
			Assert.assertTrue(owns(realization, owned));
			Assert.assertSame(owned,
				new CandidateSelectionReceipt(RULE, emission, realization, owned, List.of()).supportClause());
			Assert.assertSame(owned, alternative(realization, owned).supportClause());
		}

		CandidateRealizationSupportClause owned = realization.supportClauses().get(16);
		CandidateRealizationSupportClause equalForeign = new CandidateRealizationSupportClause(
			owned.proofDependencies(), owned.inputBindings());
		Assert.assertEquals(owned, equalForeign);
		Assert.assertNotSame(owned, equalForeign);
		Assert.assertFalse(owns(realization, equalForeign));
		IllegalArgumentException receipt = Assert.assertThrows(IllegalArgumentException.class,
			() -> new CandidateSelectionReceipt(RULE, emission, realization, equalForeign, List.of()));
		Assert.assertEquals("Candidate receipt support clause is not owned by its realization",
			receipt.getMessage());
		IllegalArgumentException alternative = Assert.assertThrows(IllegalArgumentException.class,
			() -> alternative(realization, equalForeign));
		Assert.assertEquals("EXACT_PHYSICAL_SUPPORT_CLAUSE_IDENTITY_INVALID",
			alternative.getMessage());
	}

	@Test
	public void receiptGroupUsesOwnedOrdinalWithoutRepeatedClauseScans() throws Exception {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(int index = 0; index < 32; index++)
			clauses.add(clause(index));
		CountedList<CandidateRealizationSupportClause> backing = new CountedList<>(clauses);
		@SuppressWarnings("unchecked")
		List<CandidateRealizationSupportClause> shared =
			(List<CandidateRealizationSupportClause>)shared(backing);
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementRealizationKey.local(LOCAL_EMISSION), shared);
		CandidateEmissionFact emission = new CandidateEmissionFact(
			LOCAL_EMISSION, null, null, List.of(realization));
		backing.getCalls = 0;

		Class<?> groupType = Class.forName(PlacementAnalysis.class.getName()
			+ "$CandidateReceiptDomain$ReceiptGroup");
		Constructor<?> constructor = groupType.getDeclaredConstructor(CandidateRuleKey.class,
			CandidateEmissionFact.class, CandidateEmissionRealization.class, SearchSpaceMetrics.class);
		constructor.setAccessible(true);
		Object group = constructor.newInstance(RULE, emission, realization, null);
		Method ownedIndex = groupType.getDeclaredMethod(
			"ownedClauseIndex", CandidateRealizationSupportClause.class);
		ownedIndex.setAccessible(true);
		CandidateRealizationSupportClause last = realization.supportClauses().get(31);
		Assert.assertEquals(31, ownedIndex.invoke(group, last));
		int coldGets = backing.getCalls;
		Assert.assertTrue(coldGets >= backing.size());
		Assert.assertEquals(31, ownedIndex.invoke(group, last));
		Assert.assertEquals(coldGets, backing.getCalls);
		CandidateRealizationSupportClause foreign = new CandidateRealizationSupportClause(
			last.proofDependencies(), last.inputBindings());
		Exception failure = Assert.assertThrows(Exception.class,
			() -> ownedIndex.invoke(group, foreign));
		Assert.assertEquals("Candidate support clause is outside the analysis-owned receipt domain",
			failure.getCause().getMessage());
	}

	private static ExactPhysicalModel.Alternative alternative(CandidateEmissionRealization realization,
		CandidateRealizationSupportClause clause) {
		return new ExactPhysicalModel.Alternative(OWNER, LOCAL, AuthorityKind.LEGAL_SINGLETON,
			null, null, null, null, null, null, null, List.of(), List.of(), realization, clause,
			"ownership-fixture");
	}

	private static boolean owns(CandidateEmissionRealization realization,
		CandidateRealizationSupportClause clause) throws Exception {
		Method method = CandidateEmissionRealization.class.getDeclaredMethod(
			"ownsSupportClauseIdentity", CandidateRealizationSupportClause.class);
		method.setAccessible(true);
		return (boolean)method.invoke(realization, clause);
	}

	private static List<?> shared(List<?> values) throws Exception {
		Constructor<?> constructor = Class.forName(PlacementAnalysis.class.getName()
			+ "$SharedCanonicalList").getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return (List<?>)constructor.newInstance(values);
	}

	private static int firstIdentityOrdinal(List<?> shared, Object value) throws Exception {
		Method method = shared.getClass().getDeclaredMethod("firstIdentityOrdinal", Object.class);
		method.setAccessible(true);
		return (int)method.invoke(shared, value);
	}

	private static CandidateRealizationSupportClause clause(int ordinal) {
		return new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
			PlacementProofKind.SHAPE, OWNER, "clause-" + ordinal)), List.of());
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"ownership", "main", List.of("main"), "root", "compiled");
		return new CompiledHopKey("ownership", "main", "root", "compiled",
			region, id, id);
	}

	private static final class CountedList<T> extends AbstractList<T> {
		private final List<T> values;
		private int getCalls;
		private CountedList(List<T> values) { this.values = values; }
		@Override public T get(int index) { getCalls++; return values.get(index); }
		@Override public int size() { return values.size(); }
	}
}

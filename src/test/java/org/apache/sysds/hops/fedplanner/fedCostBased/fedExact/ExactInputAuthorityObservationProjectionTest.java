package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ExactInputAuthorityObservationProjectionTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementEmissionState LOCAL_EMISSION =
		new PlacementEmissionState(LOCAL, false);
	private static final PlacementState FED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState FED_ROW_EMISSION =
		new PlacementEmissionState(FED_ROW, false);
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"observation", "main", List.of("main/0"), "root", "compiled");
	private static final CompiledHopKey OWNER = key("owner");
	private static final CompiledHopKey SOURCE_OWNER = key("source");
	private static final ValueVersionKey VERSION = version("x");

	@Test
	public void irrelevantDirectPositionCoalescesButRelevantSourceReferenceDoesNot() throws Exception {
		RelocationAction relevant = action(OWNER, 0, "relevant");
		CandidateRealizationReference sourceA = reference(SOURCE_OWNER, "a");
		CandidateRealizationReference sourceB = reference(SOURCE_OWNER, "b");

		ReceiptPair irrelevant = receiptPair(OWNER,
			CandidateRealizationInputBinding.direct(1, sourceA),
			CandidateRealizationInputBinding.direct(1, sourceB));
		Assert.assertEquals(observe(irrelevant.left, List.of(relevant)),
			observe(irrelevant.right, List.of(relevant)));

		ReceiptPair relevantPair = receiptPair(OWNER,
			CandidateRealizationInputBinding.direct(0, sourceA),
			CandidateRealizationInputBinding.direct(0, sourceB));
		Assert.assertNotEquals(observe(relevantPair.left, List.of(relevant)),
			observe(relevantPair.right, List.of(relevant)));
	}

	@Test
	public void equalButDistinctObligationOwnerDoesNotMakeDirectBindingRelevant() throws Exception {
		CompiledHopKey equalForeignOwner = key("owner");
		Assert.assertEquals(OWNER, equalForeignOwner);
		Assert.assertNotSame(OWNER, equalForeignOwner);
		RelocationAction foreign = action(equalForeignOwner, 0, "foreign-owner");
		ReceiptPair pair = receiptPair(OWNER,
			CandidateRealizationInputBinding.direct(0, reference(SOURCE_OWNER, "a")),
			CandidateRealizationInputBinding.direct(0, reference(SOURCE_OWNER, "b")));
		Assert.assertEquals(observe(pair.left, List.of(foreign)),
			observe(pair.right, List.of(foreign)));
	}

	@Test
	public void relevantSelfOwnerDirectReferenceRemainsObserved() throws Exception {
		RelocationAction relevant = action(OWNER, 0, "self");
		ReceiptPair pair = receiptPair(OWNER,
			CandidateRealizationInputBinding.direct(0, reference(OWNER, "self-a")),
			CandidateRealizationInputBinding.direct(0, reference(OWNER, "self-b")));
		Assert.assertNotEquals(observe(pair.left, List.of(relevant)),
			observe(pair.right, List.of(relevant)));
	}

	@Test
	public void relocationBindingsRemainObserved() throws Exception {
		RelocationAction first = action(OWNER, 0, "relocation-a");
		RelocationAction second = action(OWNER, 0, "relocation-b");
		CandidateRealizationReference source = reference(SOURCE_OWNER, "source");
		ReceiptPair pair = receiptPair(OWNER,
			CandidateRealizationInputBinding.relocation(0, source, first.key()),
			CandidateRealizationInputBinding.relocation(0, source, second.key()));
		List<RelocationAction> actions = List.of(first, second);
		Assert.assertNotEquals(observe(pair.left, actions), observe(pair.right, actions));
	}

	@Test
	public void projectedCategoriesAreExactForEveryCanonicalTruthCell() throws Exception {
		RelocationAction relevant = action(OWNER, 0, "truth");
		CandidateRealizationReference sourceA = reference(SOURCE_OWNER, "truth-a");
		CandidateRealizationReference sourceB = reference(SOURCE_OWNER, "truth-b");
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		bindings.add(CandidateRealizationInputBinding.direct(0, sourceA));
		bindings.add(CandidateRealizationInputBinding.direct(0, sourceB));
		for(int position = 1; position <= 8; position++)
			bindings.add(CandidateRealizationInputBinding.direct(position,
				position % 2 == 0 ? sourceA : sourceB));
		List<CandidateSelectionReceipt> receipts = receipts(OWNER, bindings);

		var receiptVariable = new ExactCategoricalSolver.Variable("receipt", receipts.size());
		var sourceVariable = new ExactCategoricalSolver.Variable("source", 2);
		var dummyVariable = new ExactCategoricalSolver.Variable("dummy", 8);
		List<CandidateRealizationReference> sources = List.of(sourceA, sourceB);
		var canonical = ExactCategoricalSolver.Factor.lazy(
			List.of(receiptVariable, sourceVariable, dummyVariable), values -> {
				var binding = receipts.get(values[0]).supportClause().inputBindings().get(0);
				return binding.inputPosition() != 0 || binding.source().equals(sources.get(values[1]))
					? 0.0 : Double.POSITIVE_INFINITY;
			});
		List<?>[] keys = new List<?>[] {
			receipts.stream().map(receipt -> {
				try { return observe(receipt, List.of(relevant)); }
				catch(Exception failure) { throw new AssertionError(failure); }
			}).toList(),
			sources,
			java.util.Collections.nCopies(8, "dummy")
		};
		ExactHardFactorObservationDecomposition.Result encoded =
			ExactHardFactorObservationDecomposition.create("projection-truth", canonical, keys);
		Assert.assertNotNull("projected fixture must form a smaller exact decomposition", encoded);
		List<int[]> maps = encoded.observations();
		int[][] representatives = new int[maps.size()][];
		for(int axis = 0; axis < maps.size(); axis++) {
			int categories = Arrays.stream(maps.get(axis)).max().orElseThrow() + 1;
			representatives[axis] = new int[categories];
			Arrays.fill(representatives[axis], -1);
			for(int value = 0; value < maps.get(axis).length; value++)
				if(representatives[axis][maps.get(axis)[value]] < 0)
					representatives[axis][maps.get(axis)[value]] = value;
		}
		var truth = encoded.solverFactors().get(encoded.solverFactors().size() - 1);
		for(int receipt = 0; receipt < receipts.size(); receipt++)
			for(int source = 0; source < sources.size(); source++)
				for(int dummy = 0; dummy < 8; dummy++) {
					int[] original = {receipt, source, dummy};
					int[] categories = {maps.get(0)[receipt], maps.get(1)[source], maps.get(2)[dummy]};
					int[] representative = {
						representatives[0][categories[0]], representatives[1][categories[1]],
						representatives[2][categories[2]]};
					long expected = Double.doubleToRawLongBits(canonical.cost(original));
					Assert.assertEquals("representative=" + Arrays.toString(original), expected,
						Double.doubleToRawLongBits(canonical.cost(representative)));
					Assert.assertEquals("encoded=" + Arrays.toString(original), expected,
						Double.doubleToRawLongBits(truth.cost(categories)));
				}
	}

	private static Object observe(CandidateSelectionReceipt receipt,
		List<RelocationAction> actions) throws Exception {
		Method observer = null;
		for(Method method : ExactPhysicalModel.class.getDeclaredMethods())
			if(method.getName().equals("receiptObservation")) {
				observer = method;
				break;
			}
		Assert.assertNotNull(observer);
		observer.setAccessible(true);
		if(observer.getParameterCount() == 2)
			return observer.invoke(null, receipt, actions);
		Method indexer = ExactPhysicalModel.class.getDeclaredMethod(
			"relevantDirectBindingPositions", List.class);
		indexer.setAccessible(true);
		Object index = indexer.invoke(null, actions);
		if(observer.getParameterCount() == 3)
			return observer.invoke(null, receipt, actions, index);
		Assert.assertEquals(4, observer.getParameterCount());
		java.util.Set<CompiledHopKey> sourceOwners =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		if(receipt != null)
			sourceOwners.add(receipt.rule().parentOccurrence());
		return observer.invoke(null, receipt, actions, index, sourceOwners);
	}

	private static ReceiptPair receiptPair(CompiledHopKey owner,
		CandidateRealizationInputBinding leftBinding,
		CandidateRealizationInputBinding rightBinding) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementRealizationKey realizationKey = PlacementRealizationKey.local(LOCAL_EMISSION);
		CandidateEmissionRealization left = new CandidateEmissionRealization(realizationKey,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of(leftBinding))));
		CandidateEmissionRealization right = new CandidateEmissionRealization(realizationKey,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of(rightBinding))));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			LOCAL_EMISSION, null, null, List.of(left, right));
		CandidateEmissionRealization merged = emission.realizations().get(0);
		List<CandidateRealizationSupportClause> clauses = merged.supportClauses();
		CandidateSelectionReceipt leftReceipt = new CandidateSelectionReceipt(
			rule, emission, merged, clauses.get(0), List.of());
		CandidateSelectionReceipt rightReceipt = new CandidateSelectionReceipt(
			rule, emission, merged, clauses.get(1), List.of());
		return new ReceiptPair(leftReceipt, rightReceipt);
	}

	private static List<CandidateSelectionReceipt> receipts(CompiledHopKey owner,
		List<CandidateRealizationInputBinding> bindings) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementRealizationKey key = PlacementRealizationKey.local(LOCAL_EMISSION);
		List<CandidateEmissionRealization> realizations = bindings.stream()
			.map(binding -> new CandidateEmissionRealization(key,
				List.of(new CandidateRealizationSupportClause(List.of(), List.of(binding)))))
			.toList();
		CandidateEmissionFact emission = new CandidateEmissionFact(
			LOCAL_EMISSION, null, null, realizations);
		CandidateEmissionRealization merged = emission.realizations().get(0);
		return merged.supportClauses().stream().map(clause -> new CandidateSelectionReceipt(
			rule, emission, merged, clause, List.of())).toList();
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, String id) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		return new CandidateRealizationReference(rule,
			PlacementRealizationKey.sourceLineage(FED_ROW_EMISSION, "lineage-" + id));
	}

	private static RelocationAction action(CompiledHopKey consumer, int position, String id) {
		DurableAnchorKey anchor = new DurableAnchorKey("anchor-" + id, FType.ROW,
			List.of(new AnchorPartition("worker-" + id + ":1234", List.of(0L, 0L), List.of(4L, 2L))));
		RelocationActionKey key = new RelocationActionKey(VERSION, FED_ROW, anchor,
			"scope-" + id, List.of(consumer));
		return new RelocationAction(key, List.of(new ObligationKey(consumer, position,
			VERSION, FED_ROW, key, "context-" + id)));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("observation", "main", "root", "compiled",
			REGION, id, id);
	}

	private static ValueVersionKey version(String id) {
		return new ValueVersionKey("observation", id, REGION, 0,
			VersionKind.ORDINARY, List.of());
	}

	private record ReceiptPair(CandidateSelectionReceipt left,
		CandidateSelectionReceipt right) { }
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class NativeDynamicProofFilteringTest {
	@Test public void uniformProductsUseAxesAndRetainTheAuthoritativeProduct() throws Exception {
		var axes = axes(10, 10, 10);
		List<NativeContinuityProof> product = product(axes);
		assertEquals(1000, product.size());
		AtomicInteger visits = new AtomicInteger();
		assertTrue(NativePlacementContinuity.retainDynamicProofs(product, false, source -> {
			visits.incrementAndGet(); return false;
		}).isEmpty());
		assertTrue("uniform rejection must inspect axes, not 1000 members", visits.get() <= 30);
		var forcedOwner = axes.get(1).get(0).source().rule().parentOccurrence();
		visits.set(0);
		assertSame(product, NativePlacementContinuity.retainDynamicProofs(product, false, source -> {
			visits.incrementAndGet(); return source.rule().parentOccurrence() == forcedOwner;
		}));
		assertTrue(visits.get() <= 30);
		assertSame(product, NativePlacementContinuity.retainDynamicProofs(product, true,
			source -> { throw new AssertionError("range-recomputing owner does not query source layouts"); }));
	}

	@Test public void mixedAxesKeepSparseHolesAndCanonicalAuthority() throws Exception {
		Random random = new Random(721908L);
		for(int trial = 0; trial < 32; trial++) {
			var axes = axes(1 + random.nextInt(4), 1 + random.nextInt(4), 1 + random.nextInt(4));
			List<NativeContinuityProof> product = product(axes);
			Set<CandidateRealizationReference> dynamic = new HashSet<>();
			for(var axis : axes) for(var binding : axis)
				if(random.nextBoolean()) dynamic.add(binding.source());
			Predicate<CandidateRealizationReference> predicate = dynamic::contains;
			List<NativeContinuityProof> explicit = new ArrayList<>(product);
			for(boolean recomputes : List.of(false, true)) {
				List<NativeContinuityProof> expected = explicit.stream().filter(proof -> recomputes
					|| proof.immediateBindings().stream().anyMatch(binding -> predicate.test(binding.source())))
					.toList();
				List<NativeContinuityProof> actual = NativePlacementContinuity.retainDynamicProofs(
					product, recomputes, predicate);
				assertEquals(expected, actual);
				assertEquals(expected.stream().map(NativeContinuityProof::normalizedSignature).toList(),
					actual.stream().map(NativeContinuityProof::normalizedSignature).toList());
				for(int member = 0; member < expected.size(); member++)
					for(int input = 0; input < expected.get(member).immediateBindings().size(); input++)
						assertSame(expected.get(member).immediateBindings().get(input).source(),
							actual.get(member).immediateBindings().get(input).source());
				assertEquals(expected, NativePlacementContinuity.retainDynamicProofs(
					explicit, recomputes, predicate));
			}
		}
	}

	private static List<List<CandidateRealizationInputBinding>> axes(int... widths) {
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
		var emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		for(int input = 0; input < widths.length; input++) {
			String name = "axis-" + input;
			var region = new ControlRegionKey("filter", "main", List.of("root"), name, "compiled");
			var owner = new CompiledHopKey("filter", "main", name, "compiled", region, name, name);
			var rule = new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.ROW)));
			List<CandidateRealizationInputBinding> axis = new ArrayList<>();
			for(int option = 0; option < widths[input]; option++)
				axis.add(CandidateRealizationInputBinding.direct(input, new CandidateRealizationReference(
					rule, PlacementRealizationKey.nativeLineage(emission, "choice-" + option))));
			axis.sort(PlacementAnalysis.canonicalComparator());
			axes.add(List.copyOf(axis));
		}
		return List.copyOf(axes);
	}

	@SuppressWarnings("unchecked")
	private static List<NativeContinuityProof> product(
		List<List<CandidateRealizationInputBinding>> axes) throws Exception {
		DurableAnchorKey pool = new DurableAnchorKey("filter-pool", FType.ROW,
			List.of(new AnchorPartition("worker:1234", List.of(0L, 0L), List.of(8L, 2L))));
		Class<?> templateClass = Class.forName(NativePlacementContinuity.class.getName()
			+ "$CandidateSupportTemplateProduct");
		Method create = templateClass.getDeclaredMethod("tryCreate", DurableAnchorKey.class,
			boolean.class, List.class);
		create.setAccessible(true);
		Object templates = create.invoke(null, pool, false, axes);
		assertNotNull(templates);
		Class<?> productClass = Class.forName(NativePlacementContinuity.class.getName()
			+ "$NativeContinuityProofProduct");
		Constructor<?> constructor = productClass.getDeclaredConstructor(DurableAnchorKey.class, templateClass);
		constructor.setAccessible(true);
		return (List<NativeContinuityProof>)constructor.newInstance(pool, templates);
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

public final class NativeContinuitySupportFixtureBridge {
	private NativeContinuitySupportFixtureBridge() { }

	public static CandidateEmissionRealization realization(String prefix, int leftWidth, int rightWidth) {
		CompiledHopKey owner = key(prefix + "-consumer");
		DurableAnchorKey seed = pool(prefix + "-seed");
		DurableAnchorKey output = pool(prefix + "-output");
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
		axes.add(axis(prefix + "-left", key(prefix + "-left-owner"), 0, leftWidth));
		axes.add(axis(prefix + "-right", key(prefix + "-right-owner"), 1, rightWidth));
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, axes);
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		return new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission, prefix + "-native"),
			new NativeContinuitySupportClauses(owner, product, output, true));
	}

	public static int materialized(CandidateEmissionRealization realization) {
		return realization.fullyMaterializedSupportClauseCount();
	}

	public static CandidateEmissionRealization nativeRelation(PlacementRealizationKey key,
		CompiledHopKey owner, DurableAnchorKey seed, DurableAnchorKey output,
		boolean exactLayout, List<List<CandidateRealizationInputBinding>> axes) {
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, exactLayout, axes);
		if(product == null)
			throw new IllegalArgumentException("Fixture axes are not a legal native support product");
		return new CandidateEmissionRealization(key,
			new NativeContinuitySupportClauses(owner, product, output, exactLayout));
	}

	public static CandidateEmissionRealization explicitNativeRelation(PlacementRealizationKey key,
		CompiledHopKey owner, DurableAnchorKey seed, DurableAnchorKey output,
		boolean exactLayout, List<List<CandidateRealizationInputBinding>> axes) {
		CandidateEmissionRealization lazy = nativeRelation(
			key, owner, seed, output, exactLayout, axes);
		return new CandidateEmissionRealization(key, List.copyOf(lazy.supportClauses()));
	}

	private static List<CandidateRealizationInputBinding> axis(String prefix,
		CompiledHopKey owner, int position, int width) {
		List<CandidateRealizationInputBinding> result = new ArrayList<>();
		int digits = Integer.toString(Math.max(0, width - 1)).length();
		for(int option = 0; option < width; option++) {
			CandidateRuleKey rule = new CandidateRuleKey(owner,
				List.of(CandidateInputState.present(FType.ROW)));
			PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
			var reference = new CandidateRealizationReference(rule,
				PlacementRealizationKey.nativeLineage(emission, prefix + '-'
					+ String.format(java.util.Locale.ROOT, "%0" + digits + "d", option)));
			result.add(CandidateRealizationInputBinding.direct(position, reference));
		}
		result.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(result);
	}

	private static DurableAnchorKey pool(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(8L, 2L))));
	}

	public static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey("native-fixture", "main",
			List.of("root"), name, "compiled");
		return new CompiledHopKey("native-fixture", "main", name, "compiled",
			region, name, name);
	}
}

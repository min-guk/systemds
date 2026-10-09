/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeSupportRebindTest {
	@Test
	public void bindingTheFinalEmissionRetainsTheCompressedRelationAndExactSelectedClause() {
		var original = NativeContinuitySupportFixtureBridge.realization("native-rebind", 3, 4);
		var selected = original.supportClauses().get(7);
		var emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		var rebound = PlacementSupportRelations.rebindRealization(original, emission);

		Assert.assertSame(emission, rebound.key().emissionState());
		Assert.assertSame("rebinding an emission must retain its admitted relation",
			original.supportClauses(), rebound.supportClauses());
		Assert.assertEquals(1, original.fullyMaterializedSupportClauseCount());
		Assert.assertEquals(1, rebound.fullyMaterializedSupportClauseCount());
		Assert.assertSame(selected, rebound.supportClauses().get(7));
		Assert.assertEquals(original.key().nativeLineage(), rebound.key().nativeLineage());
		Assert.assertEquals(original.normalizedSignature(), rebound.normalizedSignature());
	}

	@Test
	public void rebindingDoesNotBypassNativeWitnessValidationOrMaterializeRejectedMembers() {
		var original = NativeContinuitySupportFixtureBridge.realization("invalid-native-rebind", 2, 3);
		var wrongLayout = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.COL, false), false);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> PlacementSupportRelations.rebindRealization(original, wrongLayout));
		Assert.assertEquals(0, original.fullyMaterializedSupportClauseCount());
	}
}

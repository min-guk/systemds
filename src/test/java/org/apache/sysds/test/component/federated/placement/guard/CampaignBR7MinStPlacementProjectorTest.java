/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Method;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPlacementProjector;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.junit.Assert;
import org.junit.Test;

public class CampaignBR7MinStPlacementProjectorTest {
 @Test public void projectorHasExactFactsSelectionCarrierSeam() throws Exception {
  Method m=MinStExactPlacementProjector.class.getMethod("project", MinStExactCostFacts.class, MinStExactSelection.class);
  Assert.assertEquals(MinStPlacementInput.class,m.getReturnType());
 }
}

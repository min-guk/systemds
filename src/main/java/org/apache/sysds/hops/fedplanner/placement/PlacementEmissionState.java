/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Objects;

/** Exact per-occurrence authority consumed by placement emission. */
public record PlacementEmissionState(PlacementState placementState, boolean derivedFedFout) {
	public PlacementEmissionState {
		Objects.requireNonNull(placementState, "placementState");
	}

	public String normalizedSignature() {
		return placementState.normalizedSignature() + "|derivedFedFout=" + derivedFedFout;
	}
}

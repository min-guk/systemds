/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Typed value-delivery contract for one runtime DML function argument.
 *
 * <p>An {@link Kind#IDENTITY} transfer is the ordinary FunctionCallCP alias:
 * the bound value retains its output placement and, for FOUT, its FType. A
 * {@link Kind#LOCAL} transfer denotes separately selected physical authority:
 * normally a lowered {@link PlacementIdentity.LocalMaterializationActionKey},
 * or the retained local base of a selected derived-FOUT emission. This
 * relation does not itself authorize, cost, or emit either realization.</p>
 */
public final class FunctionInputTransfer {
	public enum Kind {
		IDENTITY,
		LOCAL
	}

	private static final String ARGUMENT_EVIDENCE_PREFIX = "function-argument:";

	/** True when the source can deliver the exact bound placement through a supported transfer kind. */
	public static boolean accepts(PlacementState source, PlacementState bound) {
		return kind(source, bound) != null;
	}

	/** Returns the selected transfer kind, or {@code null} when the pair is illegal. */
	public static Kind kind(PlacementState source, PlacementState bound) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(bound, "bound");
		boolean localBound = bound.execType() == ExecType.CP
			&& bound.output() == FederatedOutput.LOUT && bound.fType() == null;
		boolean federatedBound = bound.execType() == ExecType.FED
			&& bound.output() == FederatedOutput.FOUT && bound.fType() != null;
		if(localBound && source.output() == FederatedOutput.LOUT
			|| federatedBound && source.output() == FederatedOutput.FOUT
				&& source.fType() == bound.fType())
			return Kind.IDENTITY;
		// The canonical LOCAL path consumes a physical FOUT selection and
		// publishes CP/LOUT. Candidate privacy filtering and final emission
		// validation must prove either a LOCAL action or a derived-FOUT local base.
		if(source.output() == FederatedOutput.FOUT && source.fType() != null && localBound)
			return Kind.LOCAL;
		return null;
	}

	/** Recognizes the typed relation and legacy alias rows during graph migration. */
	public static boolean isArgumentConstraint(Constraint constraint) {
		Objects.requireNonNull(constraint, "constraint");
		return constraint.evidence().startsWith(ARGUMENT_EVIDENCE_PREFIX)
			&& (constraint.kind() == ConstraintKind.FUNCTION_INPUT_TRANSFER
				|| constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT);
	}

	private FunctionInputTransfer() {
		// utility class
	}
}

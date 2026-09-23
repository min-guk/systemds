/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Objects;

/** Immutable primitive evidence for independently replaying candidate privacy closure. */
public final class CandidatePrivacyClosureEvidence {
	public record Emission(String signature, String placement, String exec, String output, String fType,
		String executionFType, boolean derivedFedFout, String derivedSourcePlacement) {
		public Emission {
			Objects.requireNonNull(signature, "signature");
			Objects.requireNonNull(placement, "placement");
			Objects.requireNonNull(exec, "exec");
			Objects.requireNonNull(output, "output");
			fType = fType == null ? "-" : fType;
			executionFType = executionFType == null ? "-" : executionFType;
			derivedSourcePlacement = derivedSourcePlacement == null ? "-" : derivedSourcePlacement;
		}
	}

	public record Rule(String ruleSignature, String inputStatus, String inputFailure,
		boolean capabilityPresent, boolean profileAvailable, String capabilityExec,
		String capabilityOutput, String effectivePrivacy, boolean matrix,
		boolean federatedSource, boolean printOrPwrite, boolean dmlFunctionPlaceholder,
		boolean multiReturnBoundary, String dataOp, List<Boolean> inputPresence,
		List<Integer> protectedPayloadPositions, List<Emission> inputEmissions,
		String outputStatus, String outputFailure, List<String> outputEmissionSignatures) {
		public Rule {
			Objects.requireNonNull(ruleSignature, "ruleSignature");
			Objects.requireNonNull(inputStatus, "inputStatus");
			inputFailure = inputFailure == null ? "" : inputFailure;
			capabilityExec = capabilityExec == null ? "-" : capabilityExec;
			capabilityOutput = capabilityOutput == null ? "-" : capabilityOutput;
			Objects.requireNonNull(effectivePrivacy, "effectivePrivacy");
			dataOp = dataOp == null ? "-" : dataOp;
			inputPresence = List.copyOf(inputPresence);
			protectedPayloadPositions = List.copyOf(protectedPayloadPositions);
			inputEmissions = List.copyOf(inputEmissions);
			Objects.requireNonNull(outputStatus, "outputStatus");
			outputFailure = outputFailure == null ? "" : outputFailure;
			outputEmissionSignatures = List.copyOf(outputEmissionSignatures);
		}
	}

	public record Pass(int ordinal, List<Rule> rules) {
		public Pass {
			if(ordinal < 0)
				throw new IllegalArgumentException("privacy pass ordinal must be non-negative");
			rules = List.copyOf(rules);
		}
	}

	private final List<Pass> passes;

	public CandidatePrivacyClosureEvidence(List<Pass> passes) {
		this.passes = List.copyOf(passes);
		for(int i = 0; i < this.passes.size(); i++)
			if(this.passes.get(i).ordinal() != i)
				throw new IllegalArgumentException("privacy pass order differs");
	}

	public List<Pass> passes() { return passes; }
}

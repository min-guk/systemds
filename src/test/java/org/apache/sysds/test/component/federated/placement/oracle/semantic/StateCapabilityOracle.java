/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.federated.placement.oracle.semantic;

import java.util.List;
import java.util.Objects;

/**
 * Literal symbol-table and function-boundary capability evidence. The caller must
 * supply the reaching value and its version independently of any planner candidate.
 * A SUPPORTED result covers only the stated direct binding, not global plan legality.
 */
public final class StateCapabilityOracle {
	public enum Status { SUPPORTED, REJECTED, UNKNOWN }
	public enum Residency { LOCAL, FEDERATED, UNKNOWN }
	public enum Choice { CP_LOUT, CP_FOUT, FED_LOUT, FED_FOUT }
	public enum Scope { COMPILED, RECOMPILE, UNKNOWN }

	public record Value(String version, Residency residency, String geometry) {
		public Value {
			Objects.requireNonNull(version);
			Objects.requireNonNull(residency);
			if (residency == Residency.FEDERATED && (geometry == null || geometry.isEmpty()))
				throw new IllegalArgumentException("federated value needs concrete mapping identity");
		}
	}

	public record Verdict(Status status, String evidence) { }

	public record Read(String variable, Value reachingValue, String selectedVersion,
		Choice choice, Scope scope, boolean direct) {
		public Read {
			Objects.requireNonNull(variable);
			Objects.requireNonNull(choice);
			Objects.requireNonNull(scope);
		}
	}

	public record Write(String variable, Value inputValue, String selectedInputVersion,
		Choice choice, Scope scope, boolean direct) {
		public Write {
			Objects.requireNonNull(variable);
			Objects.requireNonNull(choice);
			Objects.requireNonNull(scope);
		}
	}

	public record FunctionOutput(String formalName, String boundName, Value returnedValue,
		Choice choice, Scope scope, boolean direct) {
		public FunctionOutput {
			Objects.requireNonNull(formalName);
			Objects.requireNonNull(boundName);
			Objects.requireNonNull(choice);
			Objects.requireNonNull(scope);
		}
	}

	public record FunctionCall(List<String> formalInputs, List<String> boundInputs,
		List<String> formalOutputs, List<String> boundOutputs, int selectedOutputIndex,
		Value returnedValue, Scope scope) {
		public FunctionCall {
			formalInputs = List.copyOf(formalInputs);
			boundInputs = List.copyOf(boundInputs);
			formalOutputs = List.copyOf(formalOutputs);
			boundOutputs = List.copyOf(boundOutputs);
			Objects.requireNonNull(scope);
		}
	}

	/** A variable instruction's symbol-table destination, independent of placement. */
	public record VariableWrite(String source, String destination, Value sourceValue,
		boolean movesToFile, Scope scope) {
		public VariableWrite {
			Objects.requireNonNull(source);
			Objects.requireNonNull(destination);
			Objects.requireNonNull(scope);
		}
	}

	public Verdict check(Read tuple) {
		Verdict pair = checkBoundary(tuple.choice(), tuple.scope());
		if (pair.status() != Status.SUPPORTED) return pair;
		if (tuple.reachingValue() == null || tuple.selectedVersion() == null)
			return unknown("reaching symbol-table value/version absent");
		if (!tuple.reachingValue().version().equals(tuple.selectedVersion()))
			return reject("selected read version is not the reaching symbol-table value");
		if (!tuple.direct()) return unknown("read transfer/materialization action is not modeled");
		return directValue(tuple.choice(), tuple.reachingValue(),
			"Recompiler.java:606-614,633-665; DataOp.java:273-277");
	}

	public Verdict check(Write tuple) {
		Verdict pair = checkBoundary(tuple.choice(), tuple.scope());
		if (pair.status() != Status.SUPPORTED) return pair;
		if (tuple.inputValue() == null || tuple.selectedInputVersion() == null)
			return unknown("write input/version absent");
		if (!tuple.inputValue().version().equals(tuple.selectedInputVersion()))
			return reject("selected write input is stale");
		if (!tuple.direct()) return unknown("write materialization action is not modeled");
		return directValue(tuple.choice(), tuple.inputValue(),
			"DataOp.java:293-297; Dag.java:1351-1395; VariableCPInstruction.java:315-323");
	}

	public Verdict check(FunctionOutput tuple) {
		Verdict pair = checkBoundary(tuple.choice(), tuple.scope());
		if (pair.status() != Status.SUPPORTED) return pair;
		if (tuple.returnedValue() == null || !tuple.direct())
			return unknown("return value or boundary materialization not proven");
		return directValue(tuple.choice(), tuple.returnedValue(),
			"DataOp.java:286-293; FunctionCallCPInstruction.java:237-256");
	}

	public Verdict check(FunctionCall tuple) {
		if (tuple.scope() == Scope.UNKNOWN) return unknown("unknown function execution context");
		if (tuple.formalInputs().size() != tuple.boundInputs().size()
			|| tuple.formalOutputs().size() != tuple.boundOutputs().size())
			return reject("function formal/bound argument count differs");
		if (tuple.selectedOutputIndex() < 0
			|| tuple.selectedOutputIndex() >= tuple.formalOutputs().size())
			return reject("selected function output index is out of bounds");
		if (tuple.returnedValue() == null || tuple.returnedValue().residency() == Residency.UNKNOWN)
			return unknown("returned runtime value is unobserved");
		if (tuple.formalOutputs().size() != 1)
			return unknown("multi-output aliasing and index bindings need a separate proof");
		return support("FunctionCallCPInstruction.java:196-203,222-256");
	}

	public Verdict check(VariableWrite tuple) {
		if (tuple.scope() == Scope.UNKNOWN) return unknown("unknown variable execution context");
		if (tuple.movesToFile()) return reject("file move does not publish a symbol-table binding");
		if (tuple.sourceValue() == null || tuple.sourceValue().residency() == Residency.UNKNOWN)
			return unknown("source symbol-table value is unobserved");
		return support("VariableCPInstruction.java:315-323,794-810");
	}

	public Verdict checkBoundary(Choice choice, Scope scope) {
		Objects.requireNonNull(choice);
		Objects.requireNonNull(scope);
		if (scope == Scope.UNKNOWN) return unknown("unknown compilation/recompilation context");
		if (scope == Scope.RECOMPILE && choice == Choice.CP_FOUT)
			return reject("recompile CP/FOUT TRead/TWrite/return boundary is forbidden by selected-plan contract; "
				+ "FederatedRefedPolicy.java:635-647,4200-4208");
		if (choice == Choice.CP_FOUT || choice == Choice.FED_LOUT)
			return unknown("noncanonical boundary pair needs an explicit materialization/lowering proof");
		return support("DataOp.java:273-297; FunctionCallCPInstruction.java:237-256");
	}

	private Verdict directValue(Choice choice, Value value, String evidence) {
		if (value.residency() == Residency.UNKNOWN) return unknown("runtime residency is unknown");
		if (choice == Choice.CP_LOUT && value.residency() == Residency.LOCAL
			|| choice == Choice.FED_FOUT && value.residency() == Residency.FEDERATED)
			return support(evidence);
		return unknown("direct boundary residency differs; transfer or materialization may be legal");
	}

	private static Verdict support(String evidence) { return new Verdict(Status.SUPPORTED, evidence); }
	private static Verdict reject(String evidence) { return new Verdict(Status.REJECTED, evidence); }
	private static Verdict unknown(String evidence) { return new Verdict(Status.UNKNOWN, evidence); }
}

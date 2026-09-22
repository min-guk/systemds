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

import java.util.Objects;

/**
 * Hand-grounded instruction capability tuples from FED runtime dispatch. This oracle
 * has no planner dependency. SUPPORTED means only that the named runtime branch is
 * reachable under this tuple, not that an entire physical plan is feasible.
 */
public final class RuntimeCapabilityOracle {
	public enum Family { MATRIX_SCALAR, MATRIX_MATRIX, AGGREGATE_UNARY, AGGREGATE_VARIANCE, OTHER }
	public enum Layout { LOCAL, ROW, COL, FULL, BROADCAST, PART, OTHER }
	public enum Output { LOUT, FOUT }
	public enum Privacy { PUBLIC, RESTRICTED, UNSPECIFIED }
	public enum Orientation { ROW, COL, OTHER }
	public enum Status { SUPPORTED, REJECTED, UNKNOWN }

	public record Input(Layout layout, int partitions) {
		public Input {
			Objects.requireNonNull(layout);
			if (partitions < 0) throw new IllegalArgumentException("negative partition count");
		}
		public static Input local() { return new Input(Layout.LOCAL, 0); }
	}

	public record Tuple(Family family, Input left, Input right, Output output,
		Privacy privacy, boolean scalarResult, Orientation orientation, boolean sameWorkerPool) {
		public Tuple {
			Objects.requireNonNull(family);
			Objects.requireNonNull(left);
			Objects.requireNonNull(right);
			Objects.requireNonNull(output);
			Objects.requireNonNull(privacy);
			Objects.requireNonNull(orientation);
		}
	}

	public record Verdict(Status status, String evidence, Layout outputLayout) { }

	public Verdict check(Tuple tuple) {
		Objects.requireNonNull(tuple);
		if (tuple.family() == Family.MATRIX_SCALAR) return matrixScalar(tuple);
		if (tuple.family() == Family.MATRIX_MATRIX) return matrixMatrix(tuple);
		if (tuple.family() == Family.AGGREGATE_UNARY || tuple.family() == Family.AGGREGATE_VARIANCE)
			return aggregateUnary(tuple);
		return unknown("uncovered instruction family");
	}

	private static Verdict matrixScalar(Tuple t) {
		if (t.left().layout() == Layout.LOCAL)
			return reject("BinaryMatrixScalarFEDInstruction.java:63-65");
		if (t.right().layout() != Layout.LOCAL)
			return unknown("scalar operand is not a local scalar");
		if (t.output() == Output.LOUT && t.left().partitions() == 0)
			return reject("BinaryMatrixScalarFEDInstruction.java:88-92");
		if (t.privacy() != Privacy.PUBLIC || t.scalarResult() || t.left().partitions() == 0)
			return unknown("privacy, shape, or mapping cardinality unproven");
		if (t.left().layout() == Layout.OTHER || t.left().layout() == Layout.PART)
			return unknown("nonstandard mapping reconstruction unproven");
		return support(t.output() == Output.LOUT ? Layout.LOCAL : t.left().layout(),
			"BinaryMatrixScalarFEDInstruction.java:58-112");
	}

	private static Verdict matrixMatrix(Tuple t) {
		if (t.left().layout() == Layout.LOCAL && t.right().layout() == Layout.LOCAL)
			return reject("BinaryMatrixMatrixFEDInstruction.java:68-75");
		if (t.left().layout() == Layout.FULL && t.left().partitions() > 1
			&& t.right().layout() == Layout.LOCAL)
			return reject("BinaryMatrixMatrixFEDInstruction.java:164-177");
		if (t.privacy() != Privacy.PUBLIC || t.scalarResult())
			return unknown("privacy or output shape unproven");
		if (t.left().layout() == Layout.FULL && t.right().layout() == Layout.FULL
			&& t.left().partitions() == 1 && t.right().partitions() == 1 && t.sameWorkerPool())
			return support(t.output() == Output.LOUT ? Layout.LOCAL : Layout.FULL,
				"BinaryMatrixMatrixFEDInstruction.java:94-101,219-249");
		return unknown("alignment, broadcast, or shape branch unmodeled");
	}

	private static Verdict aggregateUnary(Tuple t) {
		if (t.right().layout() != Layout.LOCAL)
			return unknown("aggregate unary has unexpected second operand");
		if (t.family() == Family.AGGREGATE_VARIANCE && t.output() == Output.FOUT)
			return reject("AggregateUnaryFEDInstruction.java:315-319");
		if (t.left().layout() == Layout.LOCAL)
			return reject("AggregateUnaryFEDInstruction.java:119-127");
		if (t.output() == Output.FOUT && t.scalarResult())
			return reject("AggregateUnaryFEDInstruction.java:153-156");
		if (t.privacy() != Privacy.PUBLIC || t.left().partitions() == 0)
			return unknown("privacy or mapping cardinality unproven");
		if (t.family() == Family.AGGREGATE_VARIANCE)
			return unknown("variance local path unmodeled");
		if (t.output() == Output.LOUT)
			return support(Layout.LOCAL, "AggregateUnaryFEDInstruction.java:302-312");
		if (t.orientation() == Orientation.OTHER)
			return unknown("aggregate orientation unmodeled");
		Layout in = t.left().layout();
		if (in == Layout.ROW && t.orientation() == Orientation.ROW)
			return support(Layout.ROW, "AggregateUnaryFEDInstruction.java:228-235");
		if (in == Layout.COL && t.orientation() == Orientation.COL)
			return support(Layout.COL, "AggregateUnaryFEDInstruction.java:252-262");
		if ((in == Layout.ROW && t.orientation() == Orientation.COL)
			|| (in == Layout.COL && t.orientation() == Orientation.ROW))
			return support(Layout.BROADCAST, "AggregateUnaryFEDInstruction.java:160-191");
		if (in == Layout.FULL || in == Layout.BROADCAST)
			return support(in, "AggregateUnaryFEDInstruction.java:217-223");
		return unknown("aggregate input FType unmodeled");
	}

	private static Verdict support(Layout output, String source) {
		return new Verdict(Status.SUPPORTED, source, output);
	}
	private static Verdict reject(String source) {
		return new Verdict(Status.REJECTED, source, null);
	}
	private static Verdict unknown(String reason) {
		return new Verdict(Status.UNKNOWN, reason, null);
	}
}

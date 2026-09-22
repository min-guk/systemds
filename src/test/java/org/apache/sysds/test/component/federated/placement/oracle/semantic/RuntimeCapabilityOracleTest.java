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

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.test.component.federated.placement.oracle.semantic.RuntimeCapabilityOracle.Family;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.RuntimeCapabilityOracle.Input;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.RuntimeCapabilityOracle.Layout;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.RuntimeCapabilityOracle.Orientation;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.RuntimeCapabilityOracle.Output;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.RuntimeCapabilityOracle.Privacy;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.RuntimeCapabilityOracle.Status;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.RuntimeCapabilityOracle.Tuple;

public class RuntimeCapabilityOracleTest {
	private static final RuntimeCapabilityOracle ORACLE = new RuntimeCapabilityOracle();

	private static Tuple tuple(Family family, Input left, Input right, Output output,
		Privacy privacy, boolean scalar, Orientation orientation, boolean colocated) {
		return new Tuple(family, left, right, output, privacy, scalar, orientation, colocated);
	}

	@Test public void matrixScalarRequiresFederatedMatrixAndPreservesFType() {
		Tuple valid = tuple(Family.MATRIX_SCALAR, new Input(Layout.COL, 2), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.OTHER, false);
		Assert.assertEquals(Status.SUPPORTED, ORACLE.check(valid).status());
		Assert.assertEquals(Layout.COL, ORACLE.check(valid).outputLayout());
		Tuple local = tuple(Family.MATRIX_SCALAR, Input.local(), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.OTHER, false);
		Assert.assertEquals(Status.REJECTED, ORACLE.check(local).status());
		Tuple fetch = tuple(Family.MATRIX_SCALAR, new Input(Layout.BROADCAST, 2), Input.local(),
			Output.LOUT, Privacy.PUBLIC, false, Orientation.OTHER, false);
		Assert.assertEquals(Layout.LOCAL, ORACLE.check(fetch).outputLayout());
	}

	@Test public void matrixMatrixFullPoolAndUnsupportedBranches() {
		Tuple samePool = tuple(Family.MATRIX_MATRIX, new Input(Layout.FULL, 1),
			new Input(Layout.FULL, 1), Output.FOUT, Privacy.PUBLIC, false, Orientation.OTHER, true);
		Assert.assertEquals(Status.SUPPORTED, ORACLE.check(samePool).status());
		Assert.assertEquals(Layout.FULL, ORACLE.check(samePool).outputLayout());
		Tuple differentPool = tuple(Family.MATRIX_MATRIX, new Input(Layout.FULL, 1),
			new Input(Layout.FULL, 1), Output.FOUT, Privacy.PUBLIC, false, Orientation.OTHER, false);
		Assert.assertEquals(Status.UNKNOWN, ORACLE.check(differentPool).status());
		Tuple noFed = tuple(Family.MATRIX_MATRIX, Input.local(), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.OTHER, false);
		Assert.assertEquals(Status.REJECTED, ORACLE.check(noFed).status());
		Tuple multiFull = tuple(Family.MATRIX_MATRIX, new Input(Layout.FULL, 2), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.OTHER, false);
		Assert.assertEquals(Status.REJECTED, ORACLE.check(multiFull).status());
	}

	@Test public void aggregateOrientationChangesOutputMap() {
		Tuple row = tuple(Family.AGGREGATE_UNARY, new Input(Layout.ROW, 2), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.ROW, false);
		Assert.assertEquals(Layout.ROW, ORACLE.check(row).outputLayout());
		Tuple consolidated = tuple(Family.AGGREGATE_UNARY, new Input(Layout.ROW, 2), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.COL, false);
		Assert.assertEquals(Layout.BROADCAST, ORACLE.check(consolidated).outputLayout());
		Tuple col = tuple(Family.AGGREGATE_UNARY, new Input(Layout.COL, 2), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.COL, false);
		Assert.assertEquals(Layout.COL, ORACLE.check(col).outputLayout());
		Tuple scalar = tuple(Family.AGGREGATE_UNARY, new Input(Layout.COL, 2), Input.local(),
			Output.FOUT, Privacy.PUBLIC, true, Orientation.COL, false);
		Assert.assertEquals(Status.REJECTED, ORACLE.check(scalar).status());
		Tuple variance = tuple(Family.AGGREGATE_VARIANCE, new Input(Layout.ROW, 2), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.ROW, false);
		Assert.assertEquals(Status.REJECTED, ORACLE.check(variance).status());
	}

	@Test public void unknownPrivacyAndUncoveredFamiliesCannotBecomeSupport() {
		for (Privacy privacy : new Privacy[] {Privacy.RESTRICTED, Privacy.UNSPECIFIED}) {
			Tuple candidate = tuple(Family.MATRIX_SCALAR, new Input(Layout.ROW, 2), Input.local(),
				Output.FOUT, privacy, false, Orientation.OTHER, false);
			Assert.assertEquals(Status.UNKNOWN, ORACLE.check(candidate).status());
		}
		Tuple other = tuple(Family.OTHER, new Input(Layout.ROW, 2), Input.local(),
			Output.FOUT, Privacy.PUBLIC, false, Orientation.OTHER, false);
		Assert.assertEquals(Status.UNKNOWN, ORACLE.check(other).status());
	}

	@Test public void invalidCardinalityIsRejectedBeforeCertification() {
		try {
			new Input(Layout.ROW, -1);
			Assert.fail("negative partitions must not be modeled");
		}
		catch (IllegalArgumentException expected) {
			Assert.assertEquals("negative partition count", expected.getMessage());
		}
	}
}

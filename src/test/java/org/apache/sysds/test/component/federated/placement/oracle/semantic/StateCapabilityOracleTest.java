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

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.Choice;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.FunctionCall;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.FunctionOutput;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.Read;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.Residency;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.Scope;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.Status;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.Value;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.VariableWrite;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.StateCapabilityOracle.Write;

public class StateCapabilityOracleTest {
	private static final StateCapabilityOracle ORACLE = new StateCapabilityOracle();
	private static final Value LOCAL = new Value("v2", Residency.LOCAL, null);
	private static final Value FED = new Value("v2", Residency.FEDERATED, "workers-A/ROW");

	@Test public void transientReadUsesCurrentValueAndVersion() {
		Assert.assertEquals(Status.SUPPORTED,
			ORACLE.check(new Read("X", LOCAL, "v2", Choice.CP_LOUT, Scope.COMPILED, true)).status());
		Assert.assertEquals(Status.SUPPORTED,
			ORACLE.check(new Read("X", FED, "v2", Choice.FED_FOUT, Scope.RECOMPILE, true)).status());
		Assert.assertEquals(Status.REJECTED,
			ORACLE.check(new Read("X", FED, "v1", Choice.FED_FOUT, Scope.RECOMPILE, true)).status());
		Assert.assertEquals(Status.UNKNOWN,
			ORACLE.check(new Read("X", FED, "v2", Choice.CP_LOUT, Scope.RECOMPILE, true)).status());
	}

	@Test public void transientWriteDirectAndMaterializedCasesStayDistinct() {
		Assert.assertEquals(Status.SUPPORTED,
			ORACLE.check(new Write("Y", LOCAL, "v2", Choice.CP_LOUT, Scope.COMPILED, true)).status());
		Assert.assertEquals(Status.SUPPORTED,
			ORACLE.check(new Write("Y", FED, "v2", Choice.FED_FOUT, Scope.RECOMPILE, true)).status());
		Assert.assertEquals(Status.UNKNOWN,
			ORACLE.check(new Write("Y", LOCAL, "v2", Choice.FED_FOUT, Scope.COMPILED, false)).status());
		Assert.assertEquals(Status.REJECTED,
			ORACLE.check(new Write("Y", FED, "v1", Choice.FED_FOUT, Scope.COMPILED, true)).status());
	}

	@Test public void functionOutputDirectBoundaryAndUnknownMaterialization() {
		Assert.assertEquals(Status.SUPPORTED,
			ORACLE.check(new FunctionOutput("out", "Y", LOCAL, Choice.CP_LOUT, Scope.COMPILED, true)).status());
		Assert.assertEquals(Status.SUPPORTED,
			ORACLE.check(new FunctionOutput("out", "Y", FED, Choice.FED_FOUT, Scope.COMPILED, true)).status());
		Assert.assertEquals(Status.UNKNOWN,
			ORACLE.check(new FunctionOutput("out", "Y", LOCAL, Choice.FED_FOUT, Scope.COMPILED, false)).status());
	}

	@Test public void functionCallChecksFormalBindingButNotMultiOutputAliasing() {
		Assert.assertEquals(Status.SUPPORTED,
			ORACLE.check(new FunctionCall(List.of("x"), List.of("A"), List.of("out"), List.of("Y"),
				0, FED, Scope.COMPILED)).status());
		Assert.assertEquals(Status.REJECTED,
			ORACLE.check(new FunctionCall(List.of("x"), List.of(), List.of("out"), List.of("Y"),
				0, FED, Scope.COMPILED)).status());
		Assert.assertEquals(Status.REJECTED,
			ORACLE.check(new FunctionCall(List.of("x"), List.of("A"), List.of("out"), List.of("Y"),
				1, FED, Scope.COMPILED)).status());
		Assert.assertEquals(Status.UNKNOWN,
			ORACLE.check(new FunctionCall(List.of(), List.of(), List.of("a", "b"), List.of("X", "Y"),
				1, FED, Scope.COMPILED)).status());
	}

	@Test public void variableWriteDistinguishesSymbolBindingAndFileMove() {
		Assert.assertEquals(Status.SUPPORTED,
			ORACLE.check(new VariableWrite("tmp", "Y", FED, false, Scope.COMPILED)).status());
		Assert.assertEquals(Status.REJECTED,
			ORACLE.check(new VariableWrite("tmp", "file", FED, true, Scope.COMPILED)).status());
		Assert.assertEquals(Status.UNKNOWN,
			ORACLE.check(new VariableWrite("tmp", "Y", null, false, Scope.RECOMPILE)).status());
	}

	@Test public void recompileCpfoutProhibitionIsExplicitAndOtherPairsRemainUnknown() {
		for (Choice choice : Choice.values()) {
			if (choice == Choice.CP_FOUT)
				Assert.assertEquals(Status.REJECTED, ORACLE.checkBoundary(choice, Scope.RECOMPILE).status());
		}
		Assert.assertEquals(Status.UNKNOWN, ORACLE.checkBoundary(Choice.CP_FOUT, Scope.COMPILED).status());
		Assert.assertEquals(Status.UNKNOWN, ORACLE.checkBoundary(Choice.FED_LOUT, Scope.COMPILED).status());
		Assert.assertEquals(Status.UNKNOWN, ORACLE.checkBoundary(Choice.CP_LOUT, Scope.UNKNOWN).status());
	}

	@Test public void federatedValueNeedsConcreteMappingIdentity() {
		try {
			new Value("v1", Residency.FEDERATED, null);
			Assert.fail("a bare federated label cannot certify a physical mapping");
		}
		catch (IllegalArgumentException expected) {
			Assert.assertEquals("federated value needs concrete mapping identity", expected.getMessage());
		}
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.hops.fedplanner.rules.bridge;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.codegen.SpoofCompiler.GeneratorAPI;
import org.apache.sysds.hops.codegen.SpoofFusedOp;
import org.apache.sysds.hops.codegen.SpoofFusedOp.SpoofOutputDimsType;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.junit.Assert;
import org.junit.Test;

public class OracleFacadeInnerFailurePropagationTest {
	@Test
	public void describePropagatesSpoofGeneratorInstantiationFailure() {
		OracleFacade facade = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		SpoofFusedOp spoof = new SpoofFusedOp("failingSpoof", DataType.MATRIX, ValueType.FP64,
			FailingGenerator.class, GeneratorAPI.JAVA, "failingSpoof", false,
			SpoofOutputDimsType.INPUT_DIMS);

		Assert.assertThrows(DMLRuntimeException.class, () -> facade.describe(spoof));
	}

	@Test
	public void describeAllowsSpoofWithoutGeneratorAttributes() {
		OracleFacade facade = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		SpoofFusedOp spoof = new SpoofFusedOp("generatorPending", DataType.MATRIX, ValueType.FP64,
			null, GeneratorAPI.JAVA, "generatorPending", false, SpoofOutputDimsType.INPUT_DIMS);

		Assert.assertFalse(facade.describe(spoof).attrs().containsKey("spoof.template"));
	}

	public static final class FailingGenerator {
		public FailingGenerator() {
			throw new IllegalStateException("synthetic spoof generator failure");
		}
	}
}

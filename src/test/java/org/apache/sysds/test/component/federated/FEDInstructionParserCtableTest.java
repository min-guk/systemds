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

package org.apache.sysds.test.component.federated;

import static org.junit.Assert.assertTrue;

import org.apache.sysds.runtime.instructions.FEDInstructionParser;
import org.apache.sysds.runtime.instructions.fed.CtableFEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.junit.Test;

public class FEDInstructionParserCtableTest {
	@Test
	public void parseCtable() {
		String inst = "FED°ctable°_mVar43·MATRIX·FP64°_mVar47·MATRIX·FP64°1.0·SCALAR·FP64·true°-1·true°-1·true°_mVar48·MATRIX·FP64°false°48";
		FEDInstruction parsed = FEDInstructionParser.parseSingleInstruction(inst);
		assertTrue(parsed instanceof CtableFEDInstruction);
	}
}


/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.runtime.instructions.fed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.junit.Test;

public class FEDFoutLayoutSignatureTest {
	@Test
	public void unevenAndBalancedPreservedAnchorsUseDifferentCacheKeys() {
		FederationMap uneven = anchor("localhost:19001;localhost:19002;|"
			+ "0,0,3,2;3,0,10,2;|ROW");
		FederationMap balanced = anchor("localhost:19001;localhost:19002;|"
			+ "0,0,5,2;5,0,10,2;|ROW");

		String unevenKey = FEDFoutInstruction.materializedLayoutSignature(
			uneven, FType.ROW, FType.ROW, 10, 2);
		String balancedKey = FEDFoutInstruction.materializedLayoutSignature(
			balanced, FType.ROW, FType.ROW, 10, 2);

		assertEquals(FederationUtils.deriveFedLayoutSignature(uneven), unevenKey);
		assertEquals(FederationUtils.deriveFedLayoutSignature(balanced), balancedKey);
		assertNotEquals("cache identity must retain the exact materialized geometry",
			unevenKey, balancedKey);
	}

	private static FederationMap anchor(String key) {
		return FederationUtils.buildAnchorMapFromKey(key);
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner;

import java.lang.reflect.Method;

import org.junit.Assert;
import org.junit.Test;

/** Runtime anchor signatures must expose the partitioned-axis end coordinate. */
public class FederatedRefedPolicyAnchorAxisTest {
	@Test
	public void parsesFourCoordinateAndLegacyAxisRanges() throws Exception {
		Assert.assertEquals(Long.valueOf(9), parse(
			"worker-a:1234;worker-b:1234;|0,0,4,7;4,0,9,7;|ROW"));
		Assert.assertEquals(Long.valueOf(8), parse(
			"worker-a:1234;worker-b:1234;|0,0,9,3;0,3,9,8;|COL"));
		Assert.assertEquals(Long.valueOf(9), parse(
			"worker-a:1234;worker-b:1234;|0,4;4,9;|ROW"));
		Assert.assertEquals(Long.valueOf(8), parse(
			"worker-a:1234;worker-b:1234;|0,3;3,8;|COL"));
	}

	private static Long parse(String signature) throws Exception {
		Method method = FederatedRefedPolicy.class.getDeclaredMethod(
			"parseAxisLenFromSignature", String.class);
		method.setAccessible(true);
		return (Long) method.invoke(null, signature);
	}
}

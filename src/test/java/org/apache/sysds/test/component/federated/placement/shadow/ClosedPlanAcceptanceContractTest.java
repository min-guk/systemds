/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.LinkedHashSet;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class ClosedPlanAcceptanceContractTest {
	@Test
	public void nativeAcceptancePathRemainsExplicitAndUnique() {
		List<String> predicates = ClosedPlanRelationEnumerator.acceptancePredicateIds();
		Assert.assertEquals(10, predicates.size());
		Assert.assertEquals(predicates.size(), new LinkedHashSet<>(predicates).size());
		Assert.assertEquals("DECISION_GRAPH_CONSTRAINTS", predicates.get(0));
		Assert.assertEquals("JAVA_VALIDATOR_EXCEPTION_CLASSIFICATION", predicates.get(predicates.size() - 1));
	}
}

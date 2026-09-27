/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;

final class PlacementBuilderTestAccess {
	private PlacementBuilderTestAccess() {
		// utility class
	}

	static PlacementCandidateGenerator candidateGenerator(NeutralPlacementGraphBuilder builder) {
		return field(builder, "candidateGenerator", PlacementCandidateGenerator.class);
	}

	static PlacementRelationClosure relationClosure(NeutralPlacementGraphBuilder builder) {
		return field(builder, "relationClosure", PlacementRelationClosure.class);
	}

	private static <T> T field(NeutralPlacementGraphBuilder builder, String name, Class<T> type) {
		try {
			Field field = NeutralPlacementGraphBuilder.class.getDeclaredField(name);
			field.setAccessible(true);
			return type.cast(field.get(builder));
		}
		catch(ReflectiveOperationException ex) {
			throw new AssertionError("Unable to access builder collaborator " + name, ex);
		}
	}
}

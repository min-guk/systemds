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

package org.apache.sysds.conf;

/** External configuration acquisition shared by federated planner components. */
public final class FederatedPlannerConfiguration {
	private FederatedPlannerConfiguration() {
		// utility class
	}

	public static String captureProperty(String propertyKey, String defaultValue) {
		return System.getProperty(propertyKey, defaultValue);
	}

	public static String captureTrimmedPropertyOrEnvironment(String propertyKey, String environmentKey) {
		String propertyValue = trimToNull(System.getProperty(propertyKey));
		return propertyValue != null ? propertyValue : trimToNull(System.getenv(environmentKey));
	}

	public static String captureNonEmptyPropertyOrEnvironment(String key) {
		String value = System.getProperty(key);
		return value == null || value.isEmpty() ? System.getenv(key) : value;
	}

	private static String trimToNull(String value) {
		if(value == null)
			return null;
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}

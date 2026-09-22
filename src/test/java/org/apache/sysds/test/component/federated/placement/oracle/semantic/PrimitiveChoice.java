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
import java.util.Objects;

/** One raw choice, including ordered producer bindings and action/authority identity. */
public record PrimitiveChoice(String nodeId, Exec exec, Output output, String geometry,
	String authority, List<InputBinding> bindings, SemanticIdentity identity) {
	public PrimitiveChoice {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(exec);
		Objects.requireNonNull(output);
		bindings = List.copyOf(bindings);
		Objects.requireNonNull(identity);
	}

	/** Shorthand only for bounded literal fixtures. It cannot certify full physical identity. */
	public PrimitiveChoice(String nodeId, Exec exec, Output output, String geometry,
		String authority, List<InputBinding> bindings) {
		this(nodeId, exec, output, geometry, authority, bindings, SemanticIdentity.UNSPECIFIED);
	}

	public boolean hasCompleteIdentity() { return !SemanticIdentity.UNSPECIFIED.equals(identity); }

	public record SemanticIdentity(String layoutKind, String fType, String occurrence,
		String valueVersion, String context) {
		public static final SemanticIdentity UNSPECIFIED =
			new SemanticIdentity("UNSPECIFIED", "UNSPECIFIED", "UNSPECIFIED", "UNSPECIFIED", "UNSPECIFIED");
		public SemanticIdentity {
			Objects.requireNonNull(layoutKind);
			Objects.requireNonNull(fType);
			Objects.requireNonNull(occurrence);
			Objects.requireNonNull(valueVersion);
			Objects.requireNonNull(context);
		}
	}

	public enum Exec { CP, FED }
	public enum Output { LOUT, FOUT }
	public enum Transfer { DIRECT, UPLOAD, DOWNLOAD }

	public record InputBinding(String producerId, Output required, Transfer transfer,
		String actionId, String authority) {
		public InputBinding {
			Objects.requireNonNull(producerId);
			Objects.requireNonNull(required);
			Objects.requireNonNull(transfer);
		}
	}
}

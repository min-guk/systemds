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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import org.apache.sysds.common.Types;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;

public final class HopUtils {
	private static final String PRED_TWRITE_NAME = "__pred";

	private HopUtils() {
		// utility class
	}

	public static boolean isPredTWrite(Hop hop) {
		return hop instanceof DataOp && PRED_TWRITE_NAME.equals(hop.getName());
	}

	public static boolean isPrintOrPWrite(Hop hop) {
		if (hop instanceof UnaryOp && ((UnaryOp) hop).getOp() == Types.OpOp1.PRINT) {
			return true;
		}
		return hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.PERSISTENTWRITE;
	}
}


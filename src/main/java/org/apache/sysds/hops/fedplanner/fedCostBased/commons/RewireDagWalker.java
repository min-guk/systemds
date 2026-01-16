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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;

public final class RewireDagWalker {
	public interface Visitor {
		default void beforeChildren(Hop hop, List<Hop> childHops, Context ctx) {
			// no-op
		}

		void afterChildren(Hop hop, Context ctx);
	}

	public static final class Context {
		private final Set<Long> visitedHops;
		private final Map<Long, List<Hop>> rewireTable;
		private final List<Map<String, List<Hop>>> outerTransTableList;
		private final Map<String, List<Hop>> formerTransTable;
		private final Map<String, List<Hop>> innerTransTable;
		private final boolean includeTransReadChildren;

		public Context(Set<Long> visitedHops, Map<Long, List<Hop>> rewireTable,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<String, List<Hop>> innerTransTable, boolean includeTransReadChildren) {
			this.visitedHops = visitedHops;
			this.rewireTable = rewireTable;
			this.outerTransTableList = outerTransTableList;
			this.formerTransTable = formerTransTable;
			this.innerTransTable = innerTransTable;
			this.includeTransReadChildren = includeTransReadChildren;
		}

		public Set<Long> visitedHops() {
			return visitedHops;
		}

		public Map<Long, List<Hop>> rewireTable() {
			return rewireTable;
		}

		public List<Map<String, List<Hop>>> outerTransTableList() {
			return outerTransTableList;
		}

		public Map<String, List<Hop>> formerTransTable() {
			return formerTransTable;
		}

		public Map<String, List<Hop>> innerTransTable() {
			return innerTransTable;
		}

		public boolean includeTransReadChildren() {
			return includeTransReadChildren;
		}
	}

	private RewireDagWalker() {
		// utility class
	}

	public static void walk(Hop hop, Context ctx, Visitor visitor) {
		if (hop == null || ctx == null || visitor == null) {
			return;
		}
		Set<Long> visitedHops = ctx.visitedHops();
		if (visitedHops == null || !visitedHops.add(hop.getHopID())) {
			return;
		}

		List<Hop> childHops = new ArrayList<>();
		if (hop.getInput() != null) {
			childHops.addAll(hop.getInput());
		}

		if (ctx.includeTransReadChildren()
				&& hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			String hopName = hop.getName();
			List<Hop> transChildHops = TransTableRewireUtils.resolveTransReadChildren(
					hop.getHopID(), hopName, ctx.rewireTable(), ctx.innerTransTable(),
					ctx.formerTransTable(), ctx.outerTransTableList());
			if (transChildHops != null && !transChildHops.isEmpty()) {
				childHops.addAll(transChildHops);
			}
		}

		visitor.beforeChildren(hop, childHops, ctx);
		for (Hop child : childHops) {
			walk(child, ctx, visitor);
		}
		visitor.afterChildren(hop, ctx);
	}
}

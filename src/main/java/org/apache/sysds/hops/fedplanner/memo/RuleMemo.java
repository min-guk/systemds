/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.apache.sysds.common.Types.ExecType;

/** Immutable native rule universe. Domains, source bindings and costs are owned elsewhere. */
public final class RuleMemo {
	public record Rule(ExecType exec, List<MemoPartition> inputs,
		List<MemoPartition> outputs, String variant) {
		public Rule {
			if(exec != ExecType.CP && exec != ExecType.FED)
				throw new IllegalArgumentException("Memo execution must be CP or FED");
			inputs = List.copyOf(inputs);
			outputs = List.copyOf(outputs);
			if(outputs.isEmpty())
				throw new IllegalArgumentException("A native rule must describe its output slots");
			Objects.requireNonNull(variant, "lowering variant");
		}
		public MemoPartition output() { return outputs.get(0); }
	}

	private final List<List<Rule>> rules;
	private final boolean[] complete;

	public RuleMemo(List<? extends List<Rule>> byOwner, boolean[] coverageComplete) {
		if(byOwner.size() != coverageComplete.length)
			throw new IllegalArgumentException("Each owner needs a coverage status");
		List<List<Rule>> copy = new ArrayList<>(byOwner.size());
		for(List<Rule> domain : byOwner) {
			List<Rule> distinct = domain.stream().distinct().toList();
			if(distinct.size() != domain.size())
				throw new IllegalArgumentException("Duplicate native rule in owner domain");
			copy.add(List.copyOf(domain));
		}
		rules = List.copyOf(copy);
		complete = coverageComplete.clone();
	}

	public int ownerCount() { return rules.size(); }
	public List<Rule> rules(int owner) { return rules.get(owner); }
	public Rule rule(int owner, int ordinal) { return rules.get(owner).get(ordinal); }
	public boolean complete(int owner) { return complete[owner]; }
	public boolean complete() {
		for(boolean value : complete)
			if(!value) return false;
		return true;
	}
	public BitSet[] initialDomains() {
		BitSet[] result = new BitSet[rules.size()];
		for(int owner = 0; owner < result.length; owner++) {
			result[owner] = new BitSet(rules.get(owner).size());
			result[owner].set(0, rules.get(owner).size());
		}
		return result;
	}
}

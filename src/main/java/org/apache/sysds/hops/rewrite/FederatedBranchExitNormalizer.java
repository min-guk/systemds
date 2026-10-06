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
package org.apache.sysds.hops.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.parser.DataIdentifier;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.VariableSet;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;

/** Exposes branch-exit conversion edges before immutable placement analysis. */
public final class FederatedBranchExitNormalizer {
	private FederatedBranchExitNormalizer() { }

	public static void normalize(DMLProgram program) {
		normalize(program.getStatementBlocks(), "main", Set.of());
		for(var entry : new TreeMap<>(program.getNamedNSFunctionStatementBlocks()).entrySet()) {
			FunctionStatementBlock block = entry.getValue();
			FunctionStatement function = (FunctionStatement) block.getStatement(0);
			Set<String> inputs = new LinkedHashSet<>();
			function.getInputParams().forEach(input -> inputs.add(input.getName()));
			normalize(function.getBody(), "function/" + entry.getKey(), inputs);
		}
	}

	private static Set<String> normalize(List<StatementBlock> blocks, String path,
		Set<String> incoming) {
		Set<String> bound = new LinkedHashSet<>(incoming);
		for(int i = 0; i < blocks.size(); i++) {
			StatementBlock block = blocks.get(i);
			String blockPath = path + "/" + i;
			if(block instanceof IfStatementBlock) {
				IfStatement branch = (IfStatement) block.getStatement(0);
				Set<String> thenBound = normalize(branch.getIfBody(), blockPath + "/branch-if", bound);
				Set<String> elseBound = normalize(branch.getElseBody(), blockPath + "/branch-else", bound);
				if(block.liveOut() != null) {
					TreeMap<String,DataIdentifier> joined = new TreeMap<>();
					for(String name : block.variablesUpdated().getVariableNames()) {
						DataIdentifier value = block.liveOut().getVariable(name);
						if(value != null && value.getDataType().isMatrix())
							joined.put(name, value);
					}
					appendCarrier(branch.getIfBody(), block, boundValues(joined, thenBound),
						blockPath + "/branch-if");
					appendCarrier(branch.getElseBody(), block, boundValues(joined, elseBound),
						blockPath + "/branch-else");
				}
				thenBound.retainAll(elseBound);
				bound = thenBound;
			}
			else if(block instanceof WhileStatementBlock)
				normalize(((WhileStatement) block.getStatement(0)).getBody(), blockPath + "/while-body", bound);
			else if(block instanceof ForStatementBlock)
				normalize(((ForStatement) block.getStatement(0)).getBody(), blockPath + "/for-body", bound);
			else
				bound.addAll(block.variablesUpdated().getVariableNames());
		}
		return bound;
	}

	private static TreeMap<String,DataIdentifier> boundValues(
		TreeMap<String,DataIdentifier> values, Set<String> bound) {
		TreeMap<String,DataIdentifier> result = new TreeMap<>();
		values.forEach((name, value) -> {
			if(bound.contains(name))
				result.put(name, value);
		});
		return result;
	}

	private static void appendCarrier(ArrayList<StatementBlock> body, StatementBlock owner,
		TreeMap<String,DataIdentifier> joined, String path) {
		if(joined.isEmpty())
			return;
		if(!body.isEmpty()) {
			List<Hop> roots = body.get(body.size() - 1).getHops();
			if(roots != null && roots.size() == joined.size()
				&& roots.stream().allMatch(h -> h instanceof DataOp data
					&& data.isPlannerBranchNormalization() && joined.containsKey(h.getName())))
				return;
		}
		StatementBlock carrier = new StatementBlock();
		carrier.setDMLProg(owner.getDMLProg());
		carrier.setParseInfo(owner);
		carrier.setLiveIn(new VariableSet(owner.liveOut()));
		carrier.setLiveOut(new VariableSet(owner.liveOut()));
		ArrayList<Hop> roots = new ArrayList<>();
		for(var entry : joined.entrySet()) {
			String name = entry.getKey();
			String key = path + "/" + name;
			DataIdentifier value = entry.getValue();
			DataOp read = new DataOp(name, value.getDataType(), value.getValueType(),
				OpOpData.TRANSIENTREAD, null, value.getDim1(), value.getDim2(),
				value.getNnz(), value.getBlocksize());
			read.setParseInfo(owner);
			read.setPlannerBranchNormalization(key);
			DataOp write = HopRewriteUtils.createTransientWrite(name, read);
			write.setPlannerBranchNormalization(key);
			roots.add(write);
			carrier.variablesRead().addVariable(name, value);
			carrier.variablesUpdated().addVariable(name, value);
		}
		carrier.setHops(roots);
		body.add(carrier);
	}
}

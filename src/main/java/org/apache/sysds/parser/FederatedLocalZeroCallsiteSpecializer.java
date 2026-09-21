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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.common.Types.OpOpDG;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.controlprogram.Program;

/**
 * Separates a self-contained zero-matrix call from other calls to the same
 * DML function before the final federated placement analysis. This changes
 * only function identity; it neither assigns a federated state nor filters a
 * candidate. Nested DML callees are copied as well so their formal parameters
 * cannot rejoin the original callers' placement domains.
 */
final class FederatedLocalZeroCallsiteSpecializer {
	private static final Log LOG = LogFactory.getLog(FederatedLocalZeroCallsiteSpecializer.class);
	private static final String SUFFIX = "__g009_local_zero_";
	private static final int MAX_CLONES_PER_CALLSITE = 64;

	private FederatedLocalZeroCallsiteSpecializer() { }

	static int specialize(DMLProgram program) {
		FunctionCallGraph graph = new FunctionCallGraph(program);
		if(graph.containsSecondOrderCall()) {
			LOG.warn("Skipping local-zero callsite specialization: second-order function calls are present");
			return 0;
		}
		List<FunctionOp> selected = new ArrayList<>();
		for(String namespace : new TreeSet<>(program.getNamespaces().keySet()))
			for(String name : new TreeSet<>(program.getFunctionStatementBlocks(namespace).keySet())) {
				String key = DMLProgram.constructFunctionKey(namespace, name);
				List<FunctionOp> calls = graph.getFunctionCalls(key);
				if(calls == null || calls.size() < 2 || name.contains(SUFFIX))
					continue;
				if(graph.isRecursiveFunction(key)) {
					if(calls.stream().anyMatch(FederatedLocalZeroCallsiteSpecializer::eligibleCall))
						LOG.warn("Skipping local-zero callsite specialization for recursive function " + key);
					continue;
				}
				for(FunctionOp call : calls)
					if(eligibleCall(call) && hasOtherCallerAtZeroPosition(call, calls))
						selected.add(call);
			}
		int changed = 0;
		for(FunctionOp call : selected) {
			String namespace = call.getFunctionNamespace();
			String name = call.getFunctionName();
			String cloneName = availableName(program, namespace, name + SUFFIX + changed);
			List<CloneEntry> clones = new ArrayList<>();
			try {
				buildCloneTree(program, graph, namespace, name, cloneName, clones,
					new HashMap<>(), new HashSet<>());
			}
			catch(UnsupportedCloneException ex) {
				LOG.warn("Skipping local-zero callsite specialization for " + call.getFunctionKey()
					+ ": " + ex.getMessage());
				continue;
			}
			for(CloneEntry clone : clones)
				program.addFunctionStatementBlock(clone.namespace, clone.name, clone.block);
			retarget(call, cloneName);
			changed++;
		}
		return changed;
	}

	private static boolean eligibleCall(FunctionOp call) {
		return call.getFunctionType() == FunctionType.DML && !call.isPseudoFunctionCall()
			&& call.getOutputs() == null && !localZeroFormals(call).isEmpty();
	}

	private static boolean hasOtherCallerAtZeroPosition(FunctionOp call, List<FunctionOp> calls) {
		for(String formal : localZeroFormals(call))
			for(FunctionOp other : calls) {
				Hop argument = argumentForFormal(other, formal);
				if(other != call && argument != null && !isLiteralZeroMatrix(argument))
					return true;
			}
		return false;
	}

	private static List<String> localZeroFormals(FunctionOp call) {
		String[] names = call.getInputVariableNames();
		if(names == null || names.length != call.getInput().size())
			return List.of();
		List<String> zeroFormals = new ArrayList<>();
		for(int i = 0; i < call.getInput().size(); i++)
			if(names[i] != null && isLiteralZeroMatrix(call.getInput().get(i)))
				zeroFormals.add(names[i]);
		return zeroFormals;
	}

	private static Hop argumentForFormal(FunctionOp call, String formal) {
		String[] names = call.getInputVariableNames();
		if(names == null || names.length != call.getInput().size())
			return null;
		Hop matching = null;
		for(int i = 0; i < names.length; i++)
			if(formal.equals(names[i])) {
				if(matching != null)
					return null;
				matching = call.getInput().get(i);
			}
		return matching;
	}

	private static boolean isLiteralZeroMatrix(Hop hop) {
		if(!(hop instanceof DataGenOp gen) || gen.getOp() != OpOpDG.RAND
			|| !gen.getParamIndexMap().containsKey(DataExpression.RAND_MIN)
			|| !gen.getParamIndexMap().containsKey(DataExpression.RAND_MAX))
			return false;
		return HopRewriteUtils.isLiteralOfValue(gen.getParam(DataExpression.RAND_MIN), 0)
			&& HopRewriteUtils.isLiteralOfValue(gen.getParam(DataExpression.RAND_MAX), 0);
	}

	private static void buildCloneTree(DMLProgram program, FunctionCallGraph graph,
		String namespace, String name, String cloneName, List<CloneEntry> out,
		Map<String, String> clonesByKey, Set<String> stack) {
		String key = DMLProgram.constructFunctionKey(namespace, name);
		FunctionStatementBlock original = program.getFunctionStatementBlock(namespace, name);
		if(original == null || graph.isRecursiveFunction(key) || !stack.add(key))
			throw new UnsupportedCloneException("missing or recursive nested function " + key);
		if(out.size() >= MAX_CLONES_PER_CALLSITE)
			throw new UnsupportedCloneException("nested clone limit reached for " + key);
		FunctionStatementBlock copy = (FunctionStatementBlock) original.cloneFunctionBlock();
		copy.setNondeterministic(original.isNondeterministic());
		copy.setRecompileOnce(original.isRecompileOnce());
		((FunctionStatement) copy.getStatement(0)).setName(cloneName);
		List<StatementBlock> originalBody = ((FunctionStatement) original.getStatement(0)).getBody();
		List<StatementBlock> copiedBody = ((FunctionStatement) copy.getStatement(0)).getBody();
		if(!copyBlockFlags(originalBody, copiedBody))
			throw new UnsupportedCloneException("cloned statement shape differs for " + key);
		List<FunctionOp> originalCalls = callsInBlocks(originalBody);
		List<FunctionOp> copiedCalls = callsInBlocks(copiedBody);
		if(originalCalls.size() != copiedCalls.size())
			throw new UnsupportedCloneException("cloned call count differs for " + key);
		out.add(new CloneEntry(namespace, cloneName, copy));
		clonesByKey.put(key, cloneName);
		for(int i = 0; i < originalCalls.size(); i++) {
			FunctionOp source = originalCalls.get(i);
			FunctionOp target = copiedCalls.get(i);
			if(source.getFunctionType() != target.getFunctionType()
				|| !source.getFunctionKey().equals(target.getFunctionKey()))
				throw new UnsupportedCloneException("unsupported cloned call descriptor in " + key);
			remapBuiltinOutputs(source, target);
			target.setCallOptimized(source.isCallOptimized());
			if(source.getFunctionType() != FunctionType.DML || source.isPseudoFunctionCall())
				continue;
			String childNamespace = source.getFunctionNamespace();
			String childName = source.getFunctionName();
			String childKey = source.getFunctionKey();
			String childClone = clonesByKey.get(childKey);
			if(childClone == null) {
				childClone = availableName(program, childNamespace,
					childName + SUFFIX + out.size() + "_" + cloneName);
				buildCloneTree(program, graph, childNamespace, childName, childClone, out,
					clonesByKey, stack);
			}
			retarget(target, childClone);
		}
		resetCloneOrigins(copiedBody);
		stack.remove(key);
	}

	private static void remapBuiltinOutputs(FunctionOp source, FunctionOp target) {
		if(source.getOutputs() == null && target.getOutputs() == null)
			return;
		if(source.getFunctionType() != FunctionType.MULTIRETURN_BUILTIN
			|| source.getOutputs() == null || target.getOutputs() == null
			|| source.getOutputs().size() != target.getOutputs().size())
			throw new UnsupportedCloneException("unsupported output descriptor for " + source.getFunctionKey());
		ArrayList<Hop> detached = new ArrayList<>(source.getOutputs().size());
		for(Hop output : source.getOutputs()) {
			if(!(output instanceof DataOp) || ((DataOp) output).getOp() != OpOpData.FUNCTIONOUTPUT)
				throw new UnsupportedCloneException("non-function-output descriptor for " + source.getFunctionKey());
			DataOp copy;
			try {
				copy = (DataOp) output.clone();
			}
			catch(CloneNotSupportedException ex) {
				throw new UnsupportedCloneException("unclonable output descriptor for " + source.getFunctionKey());
			}
			for(Hop input : output.getInput()) {
				int position = source.getInput().indexOf(input);
				if(position < 0)
					throw new UnsupportedCloneException("unmapped output descriptor input for " + source.getFunctionKey());
				Hop clonedInput = target.getInput().get(position);
				copy.getInput().add(clonedInput);
				clonedInput.getParent().add(copy);
			}
			copy.resetPlannerOriginForDetachedPreplanningClone();
			detached.add(copy);
		}
		target.setOutputs(detached);
	}

	private static void resetCloneOrigins(List<StatementBlock> blocks) {
		Set<Hop> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		resetCloneOrigins(blocks, visited);
	}

	private static void resetCloneOrigins(List<StatementBlock> blocks, Set<Hop> visited) {
		for(StatementBlock block : blocks) {
			if(block instanceof IfStatementBlock) {
				resetHopOrigin(((IfStatementBlock) block).getPredicateHops(), visited);
				IfStatement statement = (IfStatement) block.getStatement(0);
				resetCloneOrigins(statement.getIfBody(), visited);
				resetCloneOrigins(statement.getElseBody(), visited);
			}
			else if(block instanceof WhileStatementBlock) {
				resetHopOrigin(((WhileStatementBlock) block).getPredicateHops(), visited);
				resetCloneOrigins(((WhileStatement) block.getStatement(0)).getBody(), visited);
			}
			else if(block instanceof ForStatementBlock) {
				ForStatementBlock fsb = (ForStatementBlock) block;
				resetHopOrigin(fsb.getFromHops(), visited);
				resetHopOrigin(fsb.getToHops(), visited);
				resetHopOrigin(fsb.getIncrementHops(), visited);
				resetCloneOrigins(((ForStatement) block.getStatement(0)).getBody(), visited);
			}
			else if(block.getHops() != null)
				for(Hop root : block.getHops())
					resetHopOrigin(root, visited);
		}
	}

	private static void resetHopOrigin(Hop hop, Set<Hop> visited) {
		if(hop == null || !visited.add(hop))
			return;
		for(Hop input : hop.getInput())
			resetHopOrigin(input, visited);
		hop.resetPlannerOriginForDetachedPreplanningClone();
	}

	private static String availableName(DMLProgram program, String namespace, String base) {
		String name = base;
		for(int suffix = 1; program.getFunctionStatementBlock(namespace, name) != null; suffix++)
			name = base + "_" + suffix;
		return name;
	}

	private static void retarget(FunctionOp call, String name) {
		call.setFunctionName(name);
		call.setName(call.getFunctionNamespace() + Program.KEY_DELIM + name);
	}

	private static boolean copyBlockFlags(List<StatementBlock> original, List<StatementBlock> copies) {
		if(original.size() != copies.size())
			return false;
		for(int i = 0; i < original.size(); i++) {
			StatementBlock source = original.get(i), target = copies.get(i);
			if(source.getClass() != target.getClass())
				return false;
			target.setRecompileOnce(source.isRecompileOnce());
			if(source instanceof IfStatementBlock)
				if(!copyBlockFlags(((IfStatement) source.getStatement(0)).getIfBody(),
					((IfStatement) target.getStatement(0)).getIfBody())
					|| !copyBlockFlags(((IfStatement) source.getStatement(0)).getElseBody(),
						((IfStatement) target.getStatement(0)).getElseBody()))
					return false;
			if(source instanceof WhileStatementBlock && !copyBlockFlags(
				((WhileStatement) source.getStatement(0)).getBody(),
				((WhileStatement) target.getStatement(0)).getBody()))
				return false;
			if(source instanceof ForStatementBlock && !copyBlockFlags(
				((ForStatement) source.getStatement(0)).getBody(),
				((ForStatement) target.getStatement(0)).getBody()))
				return false;
		}
		return true;
	}

	private static List<FunctionOp> callsInBlocks(List<StatementBlock> blocks) {
		List<FunctionOp> calls = new ArrayList<>();
		Set<Hop> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for(StatementBlock block : blocks) {
			if(block instanceof IfStatementBlock) {
				collect(((IfStatementBlock) block).getPredicateHops(), calls, visited);
				IfStatement statement = (IfStatement) block.getStatement(0);
				calls.addAll(callsInBlocks(statement.getIfBody()));
				calls.addAll(callsInBlocks(statement.getElseBody()));
			}
			else if(block instanceof WhileStatementBlock) {
				collect(((WhileStatementBlock) block).getPredicateHops(), calls, visited);
				calls.addAll(callsInBlocks(((WhileStatement) block.getStatement(0)).getBody()));
			}
			else if(block instanceof ForStatementBlock) {
				ForStatementBlock fsb = (ForStatementBlock) block;
				collect(fsb.getFromHops(), calls, visited);
				collect(fsb.getToHops(), calls, visited);
				collect(fsb.getIncrementHops(), calls, visited);
				calls.addAll(callsInBlocks(((ForStatement) block.getStatement(0)).getBody()));
			}
			else if(block.getHops() != null)
				for(Hop root : block.getHops())
					collect(root, calls, visited);
		}
		return calls;
	}

	private static void collect(Hop hop, List<FunctionOp> calls, Set<Hop> visited) {
		if(hop == null || !visited.add(hop))
			return;
		for(Hop input : hop.getInput())
			collect(input, calls, visited);
		if(hop instanceof FunctionOp)
			calls.add((FunctionOp) hop);
	}

	private static final class CloneEntry {
		final String namespace;
		final String name;
		final FunctionStatementBlock block;

		CloneEntry(String namespace, String name, FunctionStatementBlock block) {
			this.namespace = namespace;
			this.name = name;
			this.block = block;
		}
	}

	private static final class UnsupportedCloneException extends RuntimeException {
		UnsupportedCloneException(String message) {
			super(message);
		}
	}
}

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

package org.apache.sysds.hops.fedplanner.fedAll;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class FederatedPlannerFedAllTest {
	private static final String ROW_FED =
		"X = federated(addresses=list(\"localhost:10001/X1\",\"localhost:10002/X2\"), "
			+ "ranges=list(list(0,0),list(4,8),list(4,0),list(8,8)))\n";
	private static final String COL_FED =
		"Y = federated(addresses=list(\"localhost:10001/Y1\",\"localhost:10002/Y2\"), "
			+ "ranges=list(list(0,0),list(8,4),list(0,4),list(8,8)))\n";

	@After
	public void clearRefedRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
	}

	@Test
	public void fedAllUsesOracleForVariance() {
		DMLProgram program = parse(ROW_FED + "V = rowVars(X)\nprint(sum(V))\n");

		new FederatedPlannerFedAll().rewriteProgram(program, null, null);

		AggUnaryOp variance = findHop(program,
			hop -> hop instanceof AggUnaryOp && ((AggUnaryOp) hop).getOp() == AggOp.VAR,
			AggUnaryOp.class);
		assertEquals(ExecType.FED, variance.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, variance.getFederatedOutput());
	}

	@Test
	public void fedAllDoesNotForcePureLocalHopsToCP() {
		DMLProgram program = parse("X = rand(rows=8, cols=8, seed=7)\nZ = X + 1\nprint(sum(Z))\n");

		new FederatedPlannerFedAll().rewriteProgram(program, null, null);

		BinaryOp plus = findHop(program,
			hop -> hop instanceof BinaryOp && ((BinaryOp) hop).getOp() == OpOp2.PLUS,
			BinaryOp.class);
		assertNull(plus.getForcedExecType());
		assertEquals(FederatedOutput.NONE, plus.getFederatedOutput());
	}

	@Test
	public void fedAllKeepsRowColMismatchLocal() {
		DMLProgram program = parse(ROW_FED + COL_FED + "Z = X + Y\nprint(sum(Z))\n");

		new FederatedPlannerFedAll().rewriteProgram(program, null, null);

		BinaryOp plus = findHop(program,
			hop -> hop instanceof BinaryOp && ((BinaryOp) hop).getOp() == OpOp2.PLUS
				&& hop.getDim1() == 8 && hop.getDim2() == 8,
			BinaryOp.class);
		assertEquals(ExecType.CP, plus.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, plus.getFederatedOutput());
		assertFalse(FederatedRefedRegistry.snapshot(findStatementBlock(program, plus).getSBID())
			.containsKey(plus.getHopID()));
	}

	@Test
	public void fedAllRefederatesSameShapeLocalInputForFederatedParent() {
		DMLProgram program = parse(ROW_FED
			+ "Z = X + (rand(rows=8, cols=8, seed=7) + 1)\n"
			+ "print(sum(Z))\n");

		new FederatedPlannerFedAll().rewriteProgram(program, null, null);

		List<BinaryOp> additions = findHops(program,
			hop -> hop instanceof BinaryOp && ((BinaryOp) hop).getOp() == OpOp2.PLUS,
			BinaryOp.class);
		BinaryOp local = additions.stream()
			.filter(hop -> !containsNamedDataInput(hop, "X", new HashSet<>()))
			.findFirst().orElse(null);
		BinaryOp federated = additions.stream()
			.filter(hop -> containsNamedDataInput(hop, "X", new HashSet<>()))
			.findFirst().orElse(null);
		assertNotNull(local);
		assertNotNull(federated);
		assertEquals(ExecType.CP, local.getForcedExecType());
		assertEquals(FederatedOutput.FOUT, local.getFederatedOutput());
		assertEquals(ExecType.FED, federated.getForcedExecType());
		assertEquals(FederatedOutput.FOUT, federated.getFederatedOutput());
		assertTrue(FederatedRefedRegistry.snapshot(findStatementBlock(program, local).getSBID())
			.containsKey(local.getHopID()));
	}

	@Test
	public void heuristicOnlyChangesPlacementAfterOracleDecision() {
		DMLProgram fedAllProgram = parse(ROW_FED
			+ "W = rand(rows=8, cols=1, seed=3)\n"
			+ "Z = X %*% W\nprint(sum(Z))\n");
		DMLProgram heuristicProgram = parse(ROW_FED
			+ "W = rand(rows=8, cols=1, seed=3)\n"
			+ "Z = X %*% W\nprint(sum(Z))\n");

		new FederatedPlannerFedAll().rewriteProgram(fedAllProgram, null, null);
		new FederatedPlannerFedHeuristic().rewriteProgram(heuristicProgram, null, null);

		AggBinaryOp fedAllMM = findHop(fedAllProgram,
			hop -> hop instanceof AggBinaryOp && ((AggBinaryOp) hop).isMatrixMultiply(), AggBinaryOp.class);
		AggBinaryOp heuristicMM = findHop(heuristicProgram,
			hop -> hop instanceof AggBinaryOp && ((AggBinaryOp) hop).isMatrixMultiply(), AggBinaryOp.class);
		assertEquals(ExecType.FED, fedAllMM.getForcedExecType());
		assertEquals(ExecType.FED, heuristicMM.getForcedExecType());
		assertEquals(FederatedOutput.FOUT, fedAllMM.getFederatedOutput());
		assertEquals(FederatedOutput.LOUT, heuristicMM.getFederatedOutput());
	}

	private static DMLProgram parse(String script) {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, Collections.emptyMap());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		boolean oldAlgebraicSimplification = OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION;
		try {
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = false;
			translator.rewriteHopsDAG(program);
		}
		finally {
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = oldAlgebraicSimplification;
		}
		return program;
	}

	private static StatementBlock findStatementBlock(DMLProgram program, Hop target) {
		for( StatementBlock block : program.getStatementBlocks() )
			if( containsHop(block.getHops(), target, new HashSet<>()) )
				return block;
		throw new AssertionError("No statement block contains hop " + target.getHopID());
	}

	private static boolean containsHop(List<Hop> roots, Hop target, Set<Long> visited) {
		if( roots == null )
			return false;
		for( Hop root : roots ) {
			if( root == target )
				return true;
			if( root != null && visited.add(root.getHopID()) && containsHop(root.getInput(), target, visited) )
				return true;
		}
		return false;
	}

	private static boolean containsNamedDataInput(Hop hop, String name, Set<Long> visited) {
		if( hop == null || !visited.add(hop.getHopID()) )
			return false;
		if( hop instanceof DataOp && name.equals(hop.getName()) )
			return true;
		for( Hop input : hop.getInput() )
			if( containsNamedDataInput(input, name, visited) )
				return true;
		return false;
	}

	private static <T extends Hop> T findHop(DMLProgram program, Predicate<Hop> predicate, Class<T> type) {
		List<T> found = findHops(program, predicate, type);
		assertFalse("Expected at least one matching hop", found.isEmpty());
		return found.get(0);
	}

	private static <T extends Hop> List<T> findHops(DMLProgram program, Predicate<Hop> predicate, Class<T> type) {
		List<T> found = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		for( StatementBlock block : program.getStatementBlocks() )
			collectHops(block.getHops(), predicate, type, visited, found);
		return found;
	}

	private static <T extends Hop> void collectHops(List<Hop> roots, Predicate<Hop> predicate, Class<T> type,
		Set<Long> visited, List<T> found) {
		if( roots == null )
			return;
		for( Hop hop : roots ) {
			if( hop == null || !visited.add(hop.getHopID()) )
				continue;
			if( predicate.test(hop) )
				found.add(type.cast(hop));
			collectHops(hop.getInput(), predicate, type, visited, found);
		}
	}
}

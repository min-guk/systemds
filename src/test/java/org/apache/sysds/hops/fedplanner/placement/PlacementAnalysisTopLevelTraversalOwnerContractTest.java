/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

/** Contract for the single neutral owner of ordered top-level program traversal. */
public class PlacementAnalysisTopLevelTraversalOwnerContractTest {
	private static final Path REWIRE = Path.of("src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/"
		+ "FederatedPlannerDpRewireTransTable.java");
	private static final Path ENUMERATOR = Path.of("src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/"
		+ "FederatedPlannerDpCostEnumerator.java");

	@Test
	public void analysisCapturesExactTopLevelStatementBlockIdentitiesInSourceOrder() {
		StatementBlock first = new StatementBlock();
		StatementBlock second = new StatementBlock();
		DMLProgram program = program(first, second);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);

		Assert.assertEquals(2, analysis.topLevelStatementBlocks().size());
		Assert.assertSame(first, analysis.topLevelStatementBlocks().get(0));
		Assert.assertSame(second, analysis.topLevelStatementBlocks().get(1));
	}

	@Test
	public void topLevelSnapshotIsUnmodifiableAndDetachedFromLaterContainerMutation() {
		StatementBlock first = new StatementBlock();
		StatementBlock second = new StatementBlock();
		DMLProgram program = program(first, second);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);

		program.getStatementBlocks().clear();

		Assert.assertEquals(2, analysis.topLevelStatementBlocks().size());
		Assert.assertSame(first, analysis.topLevelStatementBlocks().get(0));
		Assert.assertSame(second, analysis.topLevelStatementBlocks().get(1));
		assertUnmodifiable(analysis.topLevelStatementBlocks());
	}

	@Test
	public void rewireConsumesSuppliedAnalysisTraversalWithoutProgramWalk() throws Exception {
		String source = Files.readString(REWIRE);
		Assert.assertFalse(source.contains("prog.getStatementBlocks()"));
		Assert.assertTrue(source.contains("PlacementAnalysis analysis"));
		Assert.assertTrue(source.contains("analysis.topLevelStatementBlocks()"));
	}

	@Test
	public void enumeratorConsumesTheSameAnalysisTraversalWithoutProgramWalk() throws Exception {
		String source = Files.readString(ENUMERATOR);
		Assert.assertFalse(source.contains("prog.getStatementBlocks()"));
		Assert.assertTrue(source.contains("analysis.topLevelStatementBlocks()"));
		Assert.assertTrue(source.contains("rewireProgram(analysis, prog"));
	}

	private static DMLProgram program(StatementBlock... blocks) {
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(blocks)));
		return program;
	}

	private static void assertUnmodifiable(List<StatementBlock> blocks) {
		try {
			blocks.add(new StatementBlock());
			Assert.fail("top-level traversal snapshot must be unmodifiable");
		}
		catch(UnsupportedOperationException expected) {
			// expected
		}
	}
}

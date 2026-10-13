/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.junit.Assert;
import org.junit.Test;

/** Source-range shapes must reach native enumeration without mutating the compiled program. */
public class MemoFederatedModelTest {
	@Test
	public void rewrittenLogRegConstructsTheCompleteMemoModel() throws Exception {
		assertRewrittenWorkloadModel("logreg");
	}

	@Test
	public void rewrittenGlmConstructsTheCompleteMemoModel() throws Exception {
		assertRewrittenWorkloadModel("glm");
	}

	@Test
	public void rewrittenGlmNormalReturnDoesNotLeaveAFixedPrivacyUnknown() throws Exception {
		MemoProgramModel.Solution solution=rewrittenWorkloadModel("glm")
			.optimize(new MemoOptimizer.Limits(1,256,4096));
		// One node deliberately cannot certify the global optimum; it is enough to
		// detect a fixed UNKNOWN, which must stop before unrelated search begins.
		Assert.assertTrue("fixed GLM privacy must resolve before search: "+solution,
			solution.optimization().status()==MemoOptimizer.Status.OPTIMAL
			|| solution.optimization().status()==MemoOptimizer.Status.RESOURCE_EXHAUSTED);
	}

	@Test
	public void rewrittenLogRegOptimizationHonorsFiniteNodeBudget() throws Exception {
		MemoProgramModel model = rewrittenWorkloadModel("logreg");
		MemoProgramModel.Solution solution = model.optimize(new MemoOptimizer.Limits(100_000,256,4096));
		System.out.println("LOGREG_MEMO_OPTIMIZATION status=" + solution.optimization().status()
			+ " statistics=" + solution.optimization().statistics()
			+ " diagnostics=" + solution.diagnostics());
		Assert.assertNotNull(solution.optimization().status());
		Assert.assertTrue("optimizer must respect its explicit node budget: "
			+ solution.optimization().statistics(),
			solution.optimization().statistics().nodes() <= 100_000);
		Assert.assertTrue("a complete supported workload must be exact or explicitly budget-limited: "
			+ solution.diagnostics(), solution.optimization().status() == MemoOptimizer.Status.OPTIMAL
			|| solution.optimization().status() == MemoOptimizer.Status.RESOURCE_EXHAUSTED);
		Assert.assertNotNull("the bounded fixture must discover a feasible incumbent",
			solution.optimization().selectedRules());
	}

	@Test
	public void frozenLogRegAndGlmFixturesAcquireExactProtectedCanonicalSources() throws Exception {
		for(String workload : List.of("logreg", "glm")) {
			DMLProgram program = parsedFixture(workload);
			MemoProgramFacts facts = MemoProgramFacts.analyze(program);
			MemoSources sources = MemoSources.acquire(facts, null, data -> {
				if("X".equals(data.getName()))
					return sourceMetadata(Privacy.PRIVATE_AGGREGATE, 50000, 2100, "X");
				if("Y".equals(data.getName()))
					return sourceMetadata(Privacy.PRIVATE_AGGREGATE, 50000, 1, "Y");
				throw new AssertionError("Unexpected federated fixture source " + data.getName());
			});
			MemoShapes shapes = MemoShapes.analyze(facts, sources);

			Assert.assertEquals(workload, 2, sources.sources().size());
			Assert.assertEquals(workload, 2, sources.workers());
			for(MemoSources.Source source : sources.sources().values()) {
				Assert.assertEquals(MemoPartition.ROW, source.partition());
				Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, source.privacy());
				Assert.assertTrue(shapes.shape(source.owner()).toString(),
					shapes.shape(source.owner()).exact());
			}
		}
	}

	@Test
	public void protectedRepresentativeAbsLoopMultiplyAndSumHasAnExactPlan() throws Exception {
		DMLProgram program = parsed("X=matrix(1,rows=8,cols=4);"
			+ "w=matrix(1,rows=4,cols=1);A=abs(X);"
			+ "for(i in 1:3){A=abs(A);}z=A%*%w;s=sum(z);print(s);");
		UnaryOp initialAbs = findUnary(program, OpOp1.ABS);
		Hop generated = initialAbs.getInput(0);
		DataOp source = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X", -1, -1, -1, 1000);
		HopRewriteUtils.replaceChildReference(initialAbs, generated, source);

		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoSources sources = MemoSources.acquire(facts, null,
			ignored -> sourceMetadata(Privacy.PRIVATE_AGGREGATE));
		MemoRuleBuilder.Result rules = MemoRuleBuilder.build(facts, sources, new MemoNativeRules());
		MemoProgramModel model = new MemoProgramModel(facts, sources, rules,
			MemoProgramModel.Profile.defaults());
		MemoProgramModel.Solution solution = model.optimize(MemoOptimizer.Limits.exactDefaults());

		Assert.assertEquals(solution.diagnostics().toString(), MemoOptimizer.Status.OPTIMAL,
			solution.optimization().status());
		Assert.assertTrue("the representative must exercise a loop boundary", !model.heads().isEmpty());
		Assert.assertTrue("the catalog must expose a FED aggregate-binary rule",
			facts.occurrences().stream().filter(o -> o.hop() instanceof AggBinaryOp)
				.anyMatch(o -> rules.memo().rules(o.compiledOwnerId()).stream()
					.anyMatch(rule -> rule.exec() == ExecType.FED)));
		Assert.assertTrue("source-grounded protected ABS must be privacy-pruned before cost construction",
			rules.memo().rules(owner(facts,initialAbs)).stream()
				.noneMatch(rule -> rule.exec() == ExecType.CP));
		Assert.assertTrue(rules.diagnostics().toString(), rules.diagnostics().stream()
			.anyMatch(message -> message.startsWith("privacy-pruned")));
	}

	@Test
	public void canonicalSourceShapesDriveNativeRulesThroughOneRetainedAnalysis() {
		DataOp source = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X", -1, -1, -1, 1000);
		UnaryOp abs = new UnaryOp("abs", DataType.MATRIX, ValueType.FP64, OpOp1.ABS, source);
		DataOp right = new DataOp("R", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "R", 4, 3, 12, 1000);
		AggBinaryOp multiply = new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, abs, right);
		ReorgOp transpose = new ReorgOp("t", DataType.MATRIX, ValueType.FP64,
			ReOrgOp.TRANS, abs);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(multiply, transpose)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));

		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoSources sources = MemoSources.acquire(facts, null, ignored -> sourceMetadata(Privacy.PUBLIC));
		MemoShapes shapes = MemoShapes.analyze(facts, sources);
		MemoRuleBuilder.Result result = MemoRuleBuilder.build(facts, sources, new MemoNativeRules(), shapes);
		int sourceOwner = owner(facts, source), absOwner = owner(facts, abs);
		int multiplyOwner = owner(facts, multiply), transposeOwner = owner(facts, transpose);

		Assert.assertSame("rule construction must retain and reuse its one shape authority",
			shapes, result.shapes());
		Assert.assertEquals(8, result.shapes().shape(sourceOwner).rows());
		Assert.assertEquals(4, result.shapes().shape(absOwner).columns());
		Assert.assertEquals(8, result.shapes().hint(multiplyOwner).rowsA());
		Assert.assertEquals(4, result.shapes().hint(multiplyOwner).colsA());
		Assert.assertEquals(4, result.shapes().hint(multiplyOwner).rowsB());
		Assert.assertEquals(3, result.shapes().hint(multiplyOwner).colsB());
		Assert.assertTrue(result.memo().rules(absOwner).stream().anyMatch(rule ->
			rule.exec() == ExecType.FED && rule.inputs().equals(List.of(MemoPartition.ROW))
				&& rule.output() == MemoPartition.ROW));
		Assert.assertTrue(result.memo().rules(multiplyOwner).stream().anyMatch(rule ->
			rule.exec() == ExecType.FED));
		Assert.assertTrue(result.memo().rules(transposeOwner).stream().anyMatch(rule ->
			rule.exec() == ExecType.FED && rule.inputs().equals(List.of(MemoPartition.ROW))
				&& rule.output() == MemoPartition.COL));
		Assert.assertEquals("compiled source shape must remain selection-independent", -1, source.getDim1());
		Assert.assertEquals(-1, abs.getDim1());
		Assert.assertNotSame(abs, result.shapes().view(absOwner));
	}

	@Test
	public void unresolvedPrivacyDefersEarlyPruning() {
		DataOp canonical = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X", -1, -1, -1, 1000);
		DataOp unknown = new DataOp("P", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "P", 8, 4, 32, 1000);
		UnaryOp abs = new UnaryOp("A", DataType.MATRIX, ValueType.FP64, OpOp1.ABS, unknown);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(canonical,abs)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoSources sources = MemoSources.acquire(facts,null,
			ignored -> sourceMetadata(Privacy.PRIVATE_AGGREGATE));
		MemoRuleBuilder.Result result = MemoRuleBuilder.build(facts,sources,new MemoNativeRules());

		Assert.assertTrue("unknown privacy must remain DEFER rather than being pruned",
			result.memo().rules(owner(facts,abs)).stream().anyMatch(rule -> rule.exec() == ExecType.CP));
	}

	@Test
	public void sharedOwnerChecksGroundedPrivacyAfterAnUngroundedOccurrence() {
		DataOp unknown = new DataOp("P", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "P", 8, 4, 32, 1000);
		DataOp canonical = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X", -1, -1, -1, 1000);
		DataOp read = new DataOp("V", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "V", -1, -1, -1, 1000);
		UnaryOp shared = new UnaryOp("shared_abs", DataType.MATRIX, ValueType.FP64, OpOp1.ABS, read);
		List<StatementBlock> blocks = new ArrayList<>();
		for(Hop value : List.of(unknown, canonical)) {
			DataOp write = new DataOp("V", DataType.MATRIX, ValueType.FP64, value,
				OpOpData.TRANSIENTWRITE, "V");
			StatementBlock block = new StatementBlock();
			block.setHops(new ArrayList<>(List.of(write, shared)));
			blocks.add(block);
		}
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(blocks));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoSources sources = MemoSources.acquire(facts, null,
			ignored -> sourceMetadata(Privacy.PRIVATE_AGGREGATE));
		Assert.assertNull("the first occurrence has unresolved source privacy", sources.privacy(owner(facts, unknown)));
		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, sources.privacy(owner(facts, canonical)));
		MemoRuleBuilder.Result result = MemoRuleBuilder.build(facts, sources, new MemoNativeRules());
		List<Integer> occurrences = facts.occurrenceIds(shared);
		Assert.assertEquals(2, occurrences.size());
		Assert.assertFalse("the grounded second occurrence must reject CP for this shared owner",
			result.memo().rules(owner(facts, shared)).stream().anyMatch(rule -> rule.exec() == ExecType.CP));
		Assert.assertTrue("the protected occurrence must own the rejection: " + result.diagnostics(),
			result.diagnostics().stream().anyMatch(message -> message.startsWith("privacy-pruned owner="
				+ owner(facts, shared) + " occurrence=" + occurrences.get(1) + " ")));
	}

	private static FederatedSourceMetadata sourceMetadata(Privacy privacy) {
		return sourceMetadata(privacy, 8, 4, "X");
	}

	private static FederatedSourceMetadata sourceMetadata(Privacy privacy, long rows, long columns,
		String variable) {
		long split = rows / 2;
		return new FederatedSourceMetadata(privacy, List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0, 0}, new long[] {split, columns}),
				new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 19101), variable + "1")),
			new ImmutablePair<>(new FederatedRange(new long[] {split, 0}, new long[] {rows, columns}),
				new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 19102), variable + "2"))));
	}

	private static int owner(MemoProgramFacts facts, Hop hop) {
		return facts.occurrences().stream().filter(occurrence -> occurrence.hop() == hop)
			.map(MemoProgramFacts.Occurrence::compiledOwnerId).distinct().findFirst().orElseThrow();
	}

	private static DMLProgram parsed(String script) throws Exception {
		return parsed(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script);
	}

	private static DMLProgram parsedFixture(String workload) throws Exception {
		String resource = "fedplanner/training-operations/" + workload + ".dml";
		try(InputStream stream = MemoFederatedModelTest.class.getClassLoader().getResourceAsStream(resource)) {
			if(stream == null) throw new AssertionError("Missing fixture " + resource);
			return parsed(resource, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static DMLProgram parsedRewrittenFixture(String workload, long rows, long columns) throws Exception {
		String resource = "fedplanner/training-operations/" + workload + ".dml";
		try(InputStream stream = MemoFederatedModelTest.class.getClassLoader().getResourceAsStream(resource)) {
			if(stream == null) throw new AssertionError("Missing fixture " + resource);
			String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
				.replace("N = 50000", "N = " + rows)
				.replace("D = 2100", "D = " + columns)
				.replace("list(50000, 2100)", "list(" + rows + ", " + columns + ")")
				.replace("list(50000, 1)", "list(" + rows + ", 1)");
			DMLProgram program = parsed(resource, script);
			new DMLTranslator(program).rewriteHopsDAG(program);
			return program;
		}
	}

	private static void assertRewrittenWorkloadModel(String workload) throws Exception {
		rewrittenWorkloadModel(workload);
	}

	/** Package-visible workload constructor for bounded optimizer/runtime probes. */
	static MemoProgramModel rewrittenWorkloadModel(String workload) throws Exception {
		DMLProgram program = parsedRewrittenFixture(workload, 192, 8);
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		facts.requireComplete();
		MemoSources sources = MemoSources.acquire(facts, null, data -> {
			if("X".equals(data.getName()))
				return sourceMetadata(Privacy.PRIVATE_AGGREGATE, 192, 8, "X");
			if("Y".equals(data.getName()))
				return sourceMetadata(Privacy.PUBLIC, 192, 1, "Y");
			throw new AssertionError("Unexpected federated fixture source " + data.getName());
		});
		MemoRuleBuilder.Result rules = MemoRuleBuilder.build(facts, sources, new MemoNativeRules());
		MemoProgramModel model = new MemoProgramModel(facts, sources, rules,
			MemoProgramModel.Profile.defaults());

		Assert.assertEquals(workload, 2, sources.workers());
		Assert.assertTrue(workload + " must have complete native coverage: " + rules.diagnostics(),
			rules.memo().complete());
		Assert.assertTrue(workload + " must create native owner domains",
			java.util.stream.IntStream.range(0, model.memo().ownerCount())
				.anyMatch(owner -> !model.memo().rules(owner).isEmpty()));
		Assert.assertFalse(workload + " must create compatibility checks", model.checks().isEmpty());
		return model;
	}

	private static DMLProgram parsed(String file, String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(file, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static UnaryOp findUnary(DMLProgram program, OpOp1 operation) {
		for(StatementBlock block : program.getStatementBlocks()) {
			UnaryOp result = findUnary(block, operation);
			if(result != null) return result;
		}
		throw new AssertionError("Missing unary operation " + operation);
	}

	private static UnaryOp findUnary(StatementBlock block, OpOp1 operation) {
		if(block.getHops() != null)
			for(Hop root : block.getHops()) {
				UnaryOp result = findUnary(root, operation);
				if(result != null) return result;
			}
		if(block instanceof org.apache.sysds.parser.IfStatementBlock conditional) {
			IfStatement statement = (IfStatement) conditional.getStatement(0);
			for(StatementBlock child : statement.getIfBody()) {
				UnaryOp result = findUnary(child, operation);
				if(result != null) return result;
			}
			for(StatementBlock child : statement.getElseBody()) {
				UnaryOp result = findUnary(child, operation);
				if(result != null) return result;
			}
		}
		if(block instanceof org.apache.sysds.parser.WhileStatementBlock loop)
			for(StatementBlock child : ((WhileStatement) loop.getStatement(0)).getBody()) {
				UnaryOp result = findUnary(child, operation);
				if(result != null) return result;
			}
		if(block instanceof org.apache.sysds.parser.ForStatementBlock loop)
			for(StatementBlock child : ((ForStatement) loop.getStatement(0)).getBody()) {
				UnaryOp result = findUnary(child, operation);
				if(result != null) return result;
			}
		return null;
	}

	private static UnaryOp findUnary(Hop hop, OpOp1 operation) {
		if(hop instanceof UnaryOp unary && unary.getOp() == operation) return unary;
		for(Hop input : hop.getInput()) {
			UnaryOp result = findUnary(input, operation);
			if(result != null) return result;
		}
		return null;
	}
}

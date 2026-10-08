/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;

/** Same-binary benchmark over the B-11 production realization-support fixture. */
public final class ProductionRealizationSupportBenchmark {
	private record BuildSample(ExactPhysicalModel model, long nanos, long allocatedBytes,
		long logicalFactorCells, long maximumFactorCells, int finiteSupportFactors) { }
	private record Sample(long nanos, long allocatedBytes, int frozenFactors) { }

	private ProductionRealizationSupportBenchmark() { }

	public static void main(String[] arguments) throws Exception {
		if(arguments.length != 3)
			throw new IllegalArgumentException("usage: <engine-label> <warmups> <repeats>");
		String engine = arguments[0];
		int warmups = Integer.parseInt(arguments[1]);
		int repeats = Integer.parseInt(arguments[2]);
		PlacementAnalysis analysis = analysis();
		BuildSample build = buildOnce(analysis);
		for(int warmup = 0; warmup < warmups; warmup++)
			freezeOnce(build);
		Sample[] samples = new Sample[repeats];
		for(int repeat = 0; repeat < repeats; repeat++)
			samples[repeat] = freezeOnce(build);
		assertStable(samples);
		long[] wall = Arrays.stream(samples).mapToLong(Sample::nanos).sorted().toArray();
		long[] allocated = Arrays.stream(samples).mapToLong(Sample::allocatedBytes).sorted().toArray();
		Sample result = samples[0];
		System.out.printf("PRODUCTION_RESULT engine=%s fixture=flat-private variables=%d factors=%d "
			+ "logicalFactorCells=%d frozenFactors=%d finiteSupportFactors=%d buildWallMs=%.3f "
			+ "buildAllocatedBytes=%d medianFreezeWallMs=%.3f medianFreezeAllocatedBytes=%d "
			+ "warmups=%d repeats=%d%n", engine, build.model().variables().size(),
			build.model().hardFactors().size(), build.logicalFactorCells(), result.frozenFactors(),
			build.finiteSupportFactors(), build.nanos() / 1_000_000d, build.allocatedBytes(),
			wall[wall.length / 2] / 1_000_000d,
			allocated[allocated.length / 2], warmups, repeats);
	}

	private static PlacementAnalysis analysis() throws Exception {
		String script = String.join("\n",
			"UA=federated(addresses=list(\"localhost:13001/UA\"),ranges=list(list(0,0),list(16,4096)));",
			"UB=federated(addresses=list(\"localhost:13002/UB\"),ranges=list(list(0,0),list(16,4096)));",
			"S0=federated(addresses=list(\"localhost:13001/S0\"),ranges=list(list(0,0),list(16,4096)));",
			"total=0;energy=0;", "for(i in 1:3) {", "  QA0=UA+S0;QB0=UB+S0;QC0=UB*S0;",
			"  QM0=QB0/QC0;", "  total=total+sum(QA0)+sum(QM0);",
			"  energy=energy+sum(QA0*QA0)+sum(QM0*QM0);", "  S0=S0+i;", "}",
			"print(total+energy);") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
			.flatMap(block -> block.getHops().stream())
			.forEach(root -> registerPrivacy(root, visited));
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static void registerPrivacy(Hop hop, Set<Hop> visited) {
		if(hop == null || !visited.add(hop))
			return;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data,
				"S0".equals(data.getName()) ? Privacy.PUBLIC : Privacy.PRIVATE_AGGREGATE);
		for(Hop input : hop.getInput())
			registerPrivacy(input, visited);
	}

	private static BuildSample buildOnce(PlacementAnalysis analysis) {
		com.sun.management.ThreadMXBean allocations = allocationBean();
		long thread = Thread.currentThread().getId();
		long allocatedBefore = allocations == null ? -1L : allocations.getThreadAllocatedBytes(thread);
		long started = System.nanoTime();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		long nanos = System.nanoTime() - started;
		long allocatedAfter = allocations == null ? -1L : allocations.getThreadAllocatedBytes(thread);
		long logicalCells = 0L;
		long maximumCells = 1L;
		for(ExactCategoricalSolver.Factor factor : model.hardFactors()) {
			long cells = 1L;
			for(ExactCategoricalSolver.Variable variable : factor.scope())
				cells = Math.multiplyExact(cells, variable.domainSize());
			logicalCells = Math.addExact(logicalCells, cells);
			maximumCells = Math.max(maximumCells, cells);
		}
		return new BuildSample(model, nanos,
			allocatedBefore < 0L || allocatedAfter < 0L ? -1L : allocatedAfter - allocatedBefore,
			logicalCells, maximumCells, finiteSupportFactors(model));
	}

	private static Sample freezeOnce(BuildSample build) {
		com.sun.management.ThreadMXBean allocations = allocationBean();
		long thread = Thread.currentThread().getId();
		long allocatedBefore = allocations == null ? -1L : allocations.getThreadAllocatedBytes(thread);
		long started = System.nanoTime();
		ExactCategoricalSolver.Limits limits = new ExactCategoricalSolver.Limits(
			Math.max(build.maximumFactorCells(), 10_000_000L),
			Math.max(build.logicalFactorCells() * 2L, 20_000_000L));
		ExactCategoricalSolver.FrozenInputs frozen = ExactCategoricalSolver.freezeInputs(
			build.model().variables(), build.model().hardFactors(), limits);
		long nanos = System.nanoTime() - started;
		long allocatedAfter = allocations == null ? -1L : allocations.getThreadAllocatedBytes(thread);
		return new Sample(nanos,
			allocatedBefore < 0L || allocatedAfter < 0L ? -1L : allocatedAfter - allocatedBefore,
			frozen.factorCount());
	}

	/** Reflection keeps this benchmark binary compatible with OLD, which has no finite-support API. */
	private static int finiteSupportFactors(ExactPhysicalModel model) {
		try {
			Method finite = ExactCategoricalSolver.Factor.class.getDeclaredMethod("isFiniteSupport");
			finite.setAccessible(true);
			int count = 0;
			for(ExactCategoricalSolver.Factor factor : model.hardFactors())
				if((boolean)finite.invoke(factor))
					count++;
			return count;
		}
		catch(NoSuchMethodException oldEngine) {
			return -1;
		}
		catch(ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static com.sun.management.ThreadMXBean allocationBean() {
		java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		if(!(bean instanceof com.sun.management.ThreadMXBean extended)
			|| !extended.isThreadAllocatedMemorySupported())
			return null;
		if(!extended.isThreadAllocatedMemoryEnabled())
			extended.setThreadAllocatedMemoryEnabled(true);
		return extended;
	}

	private static void assertStable(Sample[] samples) {
		Sample expected = samples[0];
		for(Sample actual : samples)
			if(actual.frozenFactors() != expected.frozenFactors())
				throw new IllegalStateException("production benchmark changed between repetitions");
	}
}

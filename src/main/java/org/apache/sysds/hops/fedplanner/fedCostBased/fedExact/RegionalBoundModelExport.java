/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;

/** Opt-in, unconditioned root-model export for independent bound experiments. */
final class RegionalBoundModelExport {
	private RegionalBoundModelExport() { }

	static void configured(RegionalSearchOptimizer.State state,
		ExactPhysicalReducedSolver.CompactModel root) {
		String directory = System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "exportBoundModel");
		if(directory == null || directory.isBlank())
			return;
		try {
			Path parent = Path.of(directory);
			Files.createDirectories(parent);
			Path path = Files.createTempFile(parent, "root-", ".bin");
			write(path, root.variables(), root.factors(), state.problem.decisionCount(), state.upper,
				state.options.common().limits());
		}
		catch(IOException error) { throw new UncheckedIOException("REGIONAL_BOUND_EXPORT_FAILED", error); }
	}

	static void write(Path path, List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int decisions, double upper,
		ExactCategoricalSolver.Limits limits) throws IOException {
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> index = new IdentityHashMap<>();
		for(int v = 0; v < variables.size(); v++)
			index.put(variables.get(v), v);
		long total = 0;
		try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
			out.writeInt(0x524c4231); // RLB1; doubles retain exact model bits, +infinity is forbidden.
			out.writeDouble(upper);
			out.writeInt(decisions);
			out.writeInt(variables.size());
			for(ExactCategoricalSolver.Variable variable : variables) {
				out.writeUTF(variable.key());
				out.writeInt(variable.domainSize());
			}
			out.writeInt(factors.size());
			for(ExactCategoricalSolver.Factor factor : factors) {
				out.writeInt(factor.scope().size());
				long cells = 1;
				for(ExactCategoricalSolver.Variable variable : factor.scope()) {
					out.writeInt(index.get(variable));
					cells = Math.multiplyExact(cells, variable.domainSize());
				}
				total = Math.addExact(total, cells);
				if(cells > limits.maximumFactorCells() || total > limits.maximumMaterializedCells()
					|| cells > Integer.MAX_VALUE)
					throw new IllegalArgumentException("REGIONAL_BOUND_EXPORT_CELL_LIMIT");
				out.writeInt((int) cells);
				int[] tuple = new int[factor.scope().size()];
				for(int cell = 0; cell < cells; cell++) {
					int remaining = cell;
					for(int p = tuple.length - 1; p >= 0; p--) {
						tuple[p] = remaining % factor.scope().get(p).domainSize();
						remaining /= factor.scope().get(p).domainSize();
					}
					double cost = factor.cost(tuple);
					if(Double.isNaN(cost) || cost < 0)
						throw new IllegalArgumentException("REGIONAL_BOUND_EXPORT_INVALID_COST");
					out.writeDouble(cost);
				}
			}
		}
	}
}

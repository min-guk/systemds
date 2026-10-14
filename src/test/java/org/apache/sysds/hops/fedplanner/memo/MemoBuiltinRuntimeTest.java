/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/** Numeric execution evidence: a complete rule catalog is not an emission proof. */
public class MemoBuiltinRuntimeTest {
	@Test public void qrExecutesOnceAndBothOutputSlotsReachTheirConsumers() throws Exception {
		// SystemDS qr returns Householder H (not an explicit orthogonal Q) and R.
		// Preserve this actual native contract rather than assuming another QR API.
		List<Double> values=execute("X=matrix(\"1 2 3 5 7 11\",rows=3,cols=2);[H,R]=qr(X);"
			+ "print(sum(H));print(sum(R));print(nrow(H));print(ncol(R));");
		var decomposition=new org.apache.commons.math3.linear.QRDecomposition(
			new org.apache.commons.math3.linear.Array2DRowRealMatrix(new double[][] {{1,2},{3,5},{7,11}}));
		double h=java.util.Arrays.stream(decomposition.getH().getData()).flatMapToDouble(java.util.Arrays::stream).sum();
		double r=java.util.Arrays.stream(decomposition.getR().getData()).flatMapToDouble(java.util.Arrays::stream).sum();
		Assert.assertEquals(4,values.size());
		Assert.assertEquals(h,values.get(0),1e-10);
		Assert.assertEquals(r,values.get(1),1e-10);
		Assert.assertEquals(3,values.get(2),0);
		Assert.assertEquals(2,values.get(3),0);
	}

	@Test public void supportedCpBiasKernelsRetainTheirNativeNumericMeaning() throws Exception {
		List<Double> values=execute("X=matrix(2,3,4);B=matrix(3,2,1);"
			+ "A=bias_add(X,B);M=bias_multiply(X,B);print(sum(A));print(sum(M));");
		Assert.assertEquals(List.of(60d,72d),values);
	}

	private static List<Double> execute(String script) throws Exception {
		DMLConfig old=ConfigurationManager.getDMLConfig();
		DMLConfig config=new DMLConfig();
		config.setTextValue(DMLConfig.FEDERATED_PLANNER,"COMPILE_MEMO");
		PrintStream stdout=System.out;
		ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		try {
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
				script,new HashMap<>());
			DMLTranslator translator=new DMLTranslator(program);
			translator.liveVariableAnalysis(program);translator.validateParseTree(program);
			translator.constructHops(program);translator.rewriteHopsDAG(program);
			translator.constructLops(program,receipt -> Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,
				((FederatedPlanMemo.Receipt)receipt).solution().optimization().status()));
			Assert.assertTrue(MemoPlanRegistry.hasPlan(program));
			Program runtime=translator.getRuntimeProgram(program,config);
			System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));
			runtime.execute(ExecutionContextFactory.createContext(runtime));
			return bytes.toString(StandardCharsets.UTF_8).lines().map(String::trim)
				.filter(line -> line.matches("[-+]?[0-9.]+([eE][-+]?[0-9]+)?"))
				.map(Double::valueOf).toList();
		}
		finally { System.setOut(stdout); ConfigurationManager.setLocalConfig(old); }
	}
	@After public void cleanup() { MemoPlanRegistry.clear(); }
}

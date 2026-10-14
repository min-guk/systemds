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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.BasicProgramBlock;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.instructions.fed.MultiReturnParameterizedBuiltinFEDInstruction;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/** End-to-end proof of transformencode's heterogeneous [ROW, LOCAL] native outputs. */
public class MemoMultiOutputFederatedRuntimeTest {
	private static int port1,port2;
	private static Thread worker1,worker2;
	private static Path directory;

	@BeforeClass public static void startWorkers() throws Exception {
		port1=AutomatedTestBase.getRandomAvailablePort(); port2=AutomatedTestBase.getRandomAvailablePort();
		while(port2==port1) port2=AutomatedTestBase.getRandomAvailablePort();
		directory=Files.createTempDirectory("memo-transformencode-fed");
		writePart("private-part",0,"1,1\n2,2\n","private-aggregate");
		writePart("private-part",1,"1,3\n3,4\n","private-aggregate");
		worker1=AutomatedTestBase.startLocalFedWorkerThread(port1,1000);
		worker2=AutomatedTestBase.startLocalFedWorkerThread(port2,1000);
	}

	@AfterClass public static void stopWorkers() throws Exception {
		FederatedData.clearFederatedWorkers(); TestUtils.shutdownThreads(worker1,worker2);
		if(directory!=null && Files.exists(directory)) try(var paths=Files.walk(directory)) {
			for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	@After public void clearMemoState() { MemoPlanRegistry.clear(); }

	@Test public void transformEncodeExecutesOnceWithCanonicalRowAndLocalMetadata() throws Exception {
		Path metadataOutput=directory.resolve("metadata.csv");
		String source=privateSource();
		String script=source+"[X,M]=transformencode(target=F,"
			+"spec=\"{ids:true,recode:[1],cofeePublicRecodeMetadata:true}\");"
			+"Y=X+1;write(M,\""+metadataOutput+"\",format=\"csv\");print(sum(Y));";
		DMLConfig old=ConfigurationManager.getDMLConfig();
		String priorRelease=System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		PrintStream stdout=System.out; ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		try {
			System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,"true");
			DMLConfig config=new DMLConfig(); config.setTextValue(DMLConfig.FEDERATED_PLANNER,"COMPILE_MEMO");
			ConfigurationManager.setLocalConfig(config);
			DMLProgram dml=parse(script); DMLTranslator translator=new DMLTranslator(dml);
			AtomicReference<PlannerInvocationReceipt> captured=new AtomicReference<>();
			translator.constructLops(dml,captured::set);
			Assert.assertTrue(captured.get() instanceof FederatedPlanMemo.Receipt);
			FederatedPlanMemo.Receipt receipt=(FederatedPlanMemo.Receipt)captured.get();
			Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,receipt.solution().optimization().status());
			org.apache.sysds.hops.FunctionOp transform=(org.apache.sysds.hops.FunctionOp)receipt.facts()
				.occurrences().stream().map(MemoProgramFacts.Occurrence::hop)
				.filter(hop -> hop instanceof org.apache.sysds.hops.FunctionOp function
					&& "transformencode".equalsIgnoreCase(function.getFunctionName())).findFirst().orElseThrow();
			Assert.assertEquals(org.apache.sysds.common.Types.ExecType.FED,transform.getForcedExecType());
			Assert.assertEquals(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT,
				transform.getOutputs().get(0).getFederatedOutput());
			Assert.assertEquals(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT,
				transform.getOutputs().get(1).getFederatedOutput());

			Program runtime=translator.getRuntimeProgram(dml,config);
			long instructions=runtime.getProgramBlocks().stream().filter(BasicProgramBlock.class::isInstance)
				.map(BasicProgramBlock.class::cast).flatMap(block -> block.getInstructions().stream())
				.filter(MultiReturnParameterizedBuiltinFEDInstruction.class::isInstance).count();
			Assert.assertEquals("one multi-output owner must emit one native instruction",1,instructions);
			System.setOut(new PrintStream(bytes,true,StandardCharsets.UTF_8));
			runtime.execute(ExecutionContextFactory.createContext(runtime));
			Assert.assertEquals("encoded ROW matrix must remain numerically correct",
				java.util.List.of(25d),printedNumbers(bytes.toString(StandardCharsets.UTF_8)));
			Assert.assertTrue("the coordinator-local metadata frame must be written",Files.size(metadataOutput)>0);
		}
		finally {
			System.setOut(stdout); ConfigurationManager.setLocalConfig(old);
			if(priorRelease==null) System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
			else System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,priorRelease);
		}
	}

	@Test public void protectedInputCannotPublishCoordinatorLocalMetadata() throws Exception {
		DMLConfig old=ConfigurationManager.getDMLConfig();
		try {
			DMLConfig config=new DMLConfig(); config.setTextValue(DMLConfig.FEDERATED_PLANNER,"COMPILE_MEMO");
			ConfigurationManager.setLocalConfig(config); DMLProgram dml=parse(privateSource()
				+"[X,M]=transformencode(target=F,spec=\"{ids:true,recode:[1]}\");print(sum(X));");
			IllegalStateException failure=Assert.assertThrows(IllegalStateException.class,
				() -> new DMLTranslator(dml).constructLops(dml));
			Assert.assertTrue(failure.getMessage(),failure.getMessage().contains("INFEASIBLE"));
		}
		finally { ConfigurationManager.setLocalConfig(old); }
	}

	@Test public void explicitMetadataReleaseRequiresDeploymentOptIn() throws Exception {
		DMLConfig old=ConfigurationManager.getDMLConfig();
		String priorRelease=System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		try {
			System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
			DMLConfig config=new DMLConfig(); config.setTextValue(DMLConfig.FEDERATED_PLANNER,"COMPILE_MEMO");
			ConfigurationManager.setLocalConfig(config); DMLProgram dml=parse(privateSource()
				+"[X,M]=transformencode(target=F,"
				+"spec=\"{ids:true,recode:[1],cofeePublicRecodeMetadata:true}\");print(sum(X));");
			RuntimeException failure=Assert.assertThrows(RuntimeException.class,
				() -> new DMLTranslator(dml).constructLops(dml));
			Assert.assertTrue(failure.getMessage(),failure.getMessage().contains("INFEASIBLE")
				|| failure.getMessage().contains("not authorized"));
		}
		finally {
			ConfigurationManager.setLocalConfig(old);
			if(priorRelease==null) System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
			else System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,priorRelease);
		}
	}

	@Test public void concreteOutputSlotDriftIsRejected() throws Exception {
		DMLConfig old=ConfigurationManager.getDMLConfig();
		String priorRelease=System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		try {
			System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,"true");
			DMLConfig config=new DMLConfig(); config.setTextValue(DMLConfig.FEDERATED_PLANNER,"COMPILE_MEMO");
			ConfigurationManager.setLocalConfig(config); DMLProgram dml=parse(privateSource()
				+"[X,M]=transformencode(target=F,"
				+"spec=\"{ids:true,recode:[1],cofeePublicRecodeMetadata:true}\");print(sum(X));");
			DMLTranslator translator=new DMLTranslator(dml);
			AtomicReference<PlannerInvocationReceipt> captured=new AtomicReference<>();
			translator.constructLops(dml,captured::set);
			FederatedPlanMemo.Receipt receipt=(FederatedPlanMemo.Receipt)captured.get();
			org.apache.sysds.hops.FunctionOp transform=(org.apache.sysds.hops.FunctionOp)receipt.facts()
				.occurrences().stream().map(MemoProgramFacts.Occurrence::hop)
				.filter(hop -> hop instanceof org.apache.sysds.hops.FunctionOp function
					&& "transformencode".equalsIgnoreCase(function.getFunctionName())).findFirst().orElseThrow();
			transform.getOutputs().get(0).setFederatedOutput(
				org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT);
			RuntimeException failure=Assert.assertThrows(RuntimeException.class,
				() -> MemoPlanRegistry.verifyLowering(dml.getStatementBlocks().get(0),
					java.util.List.of(transform.getOutputs().get(0)),
					java.util.List.of(transform.getOutputs().get(0).getLops())));
			Assert.assertTrue(failure.getMessage(),failure.getMessage().contains("output")
				|| failure.getMessage().contains("federated"));
		}
		finally {
			ConfigurationManager.setLocalConfig(old);
			if(priorRelease==null) System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
			else System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY,priorRelease);
		}
	}

	private static String privateSource() {
		return "Fin=federated(addresses=list(\"localhost:"+port1+"/"
			+directory.resolve("private-part0.csv")+"\",\"localhost:"+port2+"/"
			+directory.resolve("private-part1.csv")
			+"\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));F=as.frame(Fin);";
	}

	private static java.util.List<Double> printedNumbers(String output) {
		return output.lines().map(String::trim).flatMap(line -> {
			try { return java.util.stream.Stream.of(Double.valueOf(line)); }
			catch(NumberFormatException ignored) { return java.util.stream.Stream.empty(); }
		}).toList();
	}

	private static void writePart(String prefix,int part,String values,String privacy) throws Exception {
		Path file=directory.resolve(prefix+part+".csv"); Files.writeString(file,values);
		long rows=values.lines().count();
		Files.writeString(Path.of(file+".mtd"),"{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+"\"rows\":"+rows+",\"cols\":2,\"nnz\":"+(2*rows)+",\"format\":\"csv\","
			+"\"privacy\":\""+privacy+"\"}");
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>()); DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return program;
	}
}

/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;

/** Test-only FED fixtures that retain two-worker geometry without compile-time worker RPCs. */
final class CampaignBG014HermeticPlannerFixtureFactory {
	private CampaignBG014HermeticPlannerFixtureFactory() {
	}

	static DMLProgram compile(String id) throws Exception {
		String script;
		boolean rewrite;
		switch(id) {
			case "B-11":
				script = lines(localFederatedRow("X"), "Y=X+1;", "print(sum(Y));");
				rewrite = true;
				break;
			case "B-21":
				script = b21Script();
				rewrite = false;
				break;
			default:
				throw new IllegalArgumentException("Unsupported hermetic G014 fixture " + id);
		}

		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite)
			translator.rewriteHopsDAG(program);
		return program;
	}

	private static String b21Script() throws Exception {
		Path data = Files.createTempFile("g014-b21-", ".data");
		Path mtd = Path.of(data.toString() + ".mtd");
		Files.writeString(data, "");
		Files.writeString(mtd, "{\"data_type\":\"matrix\"," +
			"\"value_type\":\"double\",\"format\":\"text\"," +
			"\"rows\":4,\"cols\":2,\"nnz\":0,\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		mtd.toFile().deleteOnExit();
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		return lines("f=function(matrix[double] X) return (matrix[double] Y){Y=rowSums(X);}",
			"A_LOCAL=read(\"" + path + "\");",
			"A=federated(local_matrix=A_LOCAL,addresses=list(\"localhost:1234\",\"localhost:1235\")," +
			"ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"Z=sum(A);", "Y=f(A);", "print(Z+sum(Y));");
	}

	private static String localFederatedRow(String variable) {
		return variable + "_LOCAL=matrix(0,4,2);\n" + variable
			+ "=federated(local_matrix=" + variable + "_LOCAL,"
			+ "addresses=list(\"localhost:1234\",\"localhost:1235\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
	}

	private static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}
}

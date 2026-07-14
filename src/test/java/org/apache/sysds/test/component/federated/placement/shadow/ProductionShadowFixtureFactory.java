/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;

/** Independently compiled production inputs for the B-01..B-22 shadow corpus. */
public final class ProductionShadowFixtureFactory {
	private static final List<String> IDS = List.of("B-01", "B-02", "B-03", "B-04", "B-05", "B-06",
		"B-07", "B-08", "B-09", "B-10", "B-11", "B-12", "B-13", "B-14", "B-15", "B-16",
		"B-17", "B-18", "B-19", "B-20", "B-21", "B-22");

	public static List<String> ids() {
		return IDS;
	}

	public static DMLProgram compile(String id) throws Exception {
		String script = script(id);
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	public static Map<String, String> scripts() {
		Map<String, String> result = new java.util.LinkedHashMap<>();
		for(String id : IDS)
			result.put(id, script(id));
		return Collections.unmodifiableMap(result);
	}

	private static String script(String id) {
		switch(id) {
			case "B-01": return lines("X=matrix(1,2,2);", "X=X+1;", "Y=X;", "print(sum(Y));");
			case "B-02": return branch("X=A+1", "X=A+1");
			case "B-03": return branch("X=A+1", "X=matrix(0,2,2)");
			case "B-04": return lines("A=matrix(1,2,2);", "if(TRUE){X=A+1;}else{X=A-1;}", "print(sum(X));");
			case "B-05": return lines("X=matrix(1,2,2);", "i=1;", "while(i<=7){X=X+1;i=i+1;}",
				"print(sum(X));");
			case "B-06": return lines("X=matrix(1,2,2);", "i=1;", "while(i<=2){X=matrix(i,2,2);i=i+1;}",
				"print(sum(X));");
			case "B-07": return functionProgram(false, false);
			case "B-08": return functionProgram(true, false);
			case "B-09": return lines("X=matrix(1,2,2);", "i=1;", "while(i<=2){X=X+1;i=i+1;}",
				"print(sum(X));");
			case "B-10": return lines("A=matrix(1,2,2);", "S=A+1;", "R1=S*2;", "R2=S*3;",
				"print(sum(R1)+sum(R2));");
			case "B-11": return lines(federatedRow(), "Y=X+1;", "print(sum(Y));");
			case "B-12": return lines("X=matrix(1,4,2);", "Y=X+1;", "print(sum(Y));");
			case "B-13": return lines(federatedPart(), "Y=X+1;", "print(sum(Y));");
			case "B-14": return lines("X=matrix(1,2,2);", "Y=X+1;", "print(sum(Y));");
			case "B-15": return lines("X=matrix(1,2,3);", "Y=matrix(1,3,4);", "Z=X%*%Y;", "print(sum(Z));");
			case "B-16": return lines("X=matrix(1,2,2);", "Y=X+1;", "print(sum(Y));");
			case "B-17": return functionProgram(false, true);
			case "B-18": return lines("X=matrix(1,2,2);", "i=1;", "while(i<=2){if(sum(X)>0){X=X+1;}else{X=matrix(0,2,2);}i=i+1;}",
				"print(sum(X));");
			case "B-19": return branch("X=A+1", "X=matrix(0,2,2)");
			case "B-20": return lines("A=matrix(1,2,2);", "X=A;", "i=1;",
				"while(i<=2){if(sum(X)>0){X=X+1;}else{X=X-1;}i=i+1;}", "print(sum(X));");
			case "B-21": return lines("f=function(matrix[double] X) return (matrix[double] Y){Y=rowSums(X);}",
				"A=matrix(1,2,2);", "Y=f(A);", "print(sum(Y));");
			case "B-22": return lines(federatedRow(), "S=matrix(1,4,2);", "Y1=X+S;", "Y2=X-S;",
				"print(sum(Y1)+sum(Y2));");
			default: throw new IllegalArgumentException("Unknown production shadow fixture " + id);
		}
	}

	private static String branch(String thenBody, String elseBody) {
		return lines("A=matrix(1,2,2);", "p=sum(A)>0;", "if(p){" + thenBody + ";}else{" + elseBody + ";}",
			"print(sum(X));");
	}

	private static String functionProgram(boolean deadOutput, boolean twoCalls) {
		String tail = deadOutput ? "print(sum(X));" : twoCalls
			? "Y1=f(X);Y2=f(X+1);print(sum(Y1)+sum(Y2));" : "Y=f(X);print(sum(Y));";
		return lines("f=function(matrix[double] A) return (matrix[double] B){B=A+1;}",
			"X=matrix(1,2,2);", tail);
	}

	private static String federatedRow() {
		return "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
	}

	private static String federatedPart() {
		return "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,1),list(2,1),list(4,2)));";
	}

	private static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}

	private ProductionShadowFixtureFactory() {
		// utility class
	}
}

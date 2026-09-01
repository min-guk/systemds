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

package org.apache.sysds.test.component.federated.placement.oracle.selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Builder;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Choice;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Execution;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Node;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Output;

/** Explicit S-01..S-08 fixture family. */
public final class SelectorOracleFixtures {
	private SelectorOracleFixtures() {
	}

	public static ExplicitSelectorGraph independentHops() {
		return new Builder("S-01", 1)
			.addNode(ordinaryNode("a"))
			.addNode(ordinaryNode("b"))
			.build();
	}

	public static ExplicitSelectorGraph parentChildFTypeConflict() {
		return new Builder("S-02", 2)
			.addNode(Node.of("child", local("local"), fedFout("row", "upload:child")))
			.addNode(Node.of("parent", local("local"), fedFout("full")))
			.addEdge("child", "parent")
			.addConstraint(ExplicitSelectorGraph.forbidPair("child", "row", "parent", "full"))
			.build();
	}

	public static ExplicitSelectorGraph sharedDiamond() {
		return new Builder("S-03", 3)
			.addNode(ordinaryNode("shared"))
			.addNode(ordinaryNode("left"))
			.addNode(ordinaryNode("right"))
			.addEdge("shared", "left")
			.addEdge("shared", "right")
			.build();
	}

	public static ExplicitSelectorGraph sharedRelocation() {
		return new Builder("S-04", 4)
			.addNode(Node.of("value", local("local"), Choice.of("uploaded", Execution.CP, Output.FOUT, "upload:value")))
			.addNode(Node.of("left", local("local"), fedFout("fed")))
			.addNode(Node.of("right", local("local"), fedFout("fed")))
			.addEdge("value", "left")
			.addEdge("value", "right")
			.addConstraint(ExplicitSelectorGraph.requireChoiceWhen("left", "fed", "value", "uploaded"))
			.addConstraint(ExplicitSelectorGraph.requireChoiceWhen("right", "fed", "value", "uploaded"))
			.build();
	}

	public static ExplicitSelectorGraph fedBeforeFout() {
		return new Builder("S-05", 5)
			.addNode(Node.of("fedGain", local("local"), fedLout("fed")))
			.addNode(Node.of("foutOne", local("local"), Choice.of("fout", Execution.CP, Output.FOUT)))
			.addNode(Node.of("foutTwo", local("local"), Choice.of("fout", Execution.CP, Output.FOUT)))
			.addConstraint(ExplicitSelectorGraph.forbidPair("fedGain", "fed", "foutOne", "fout"))
			.addConstraint(ExplicitSelectorGraph.forbidPair("fedGain", "fed", "foutTwo", "fout"))
			.build();
	}

	public static ExplicitSelectorGraph fewerRelocations() {
		return new Builder("S-06", 6)
			.addNode(Node.of("a", fedFout("shared", "upload:shared"), fedFout("split", "upload:a")))
			.addNode(Node.of("b", fedFout("shared", "upload:shared"), fedFout("split", "upload:b")))
			.addConstraint(ExplicitSelectorGraph.forbidPair("a", "shared", "b", "split"))
			.addConstraint(ExplicitSelectorGraph.forbidPair("a", "split", "b", "shared"))
			.build();
	}

	public static ExplicitSelectorGraph stableTie() {
		return new Builder("S-07", 7)
			.addNode(Node.of("node", fedLout("alpha"), fedLout("omega")))
			.build();
	}

	public static List<ExplicitSelectorGraph> generatedCorpus() {
		List<ExplicitSelectorGraph> corpus = new ArrayList<>();
		for (int size = 2; size <= 6; size++) {
			for (long seed : new long[] {11, 29, 47})
				corpus.add(generatedGraph(size, seed));
		}
		return corpus;
	}

	private static ExplicitSelectorGraph generatedGraph(int size, long seed) {
		Random random = new Random(seed * 31 + size);
		Builder builder = new Builder("S-08-n" + size, seed);
		for (int i = 0; i < size; i++) {
			Choice local = local("local");
			Choice fedLocal = fedLout("fed-lout");
			Choice fedOutput = fedFout("fed-fout", "upload:r" + random.nextInt(Math.max(1, size / 2)));
			if (i % 3 == 0)
				builder.addNode(Node.heuristicRestricted("n" + i, List.of("local", "fed-lout"), local, fedLocal, fedOutput));
			else
				builder.addNode(Node.of("n" + i, local, fedLocal, fedOutput));
			if (i > 0) {
				int parent = random.nextInt(i);
				builder.addEdge("n" + parent, "n" + i);
				if (random.nextBoolean())
					builder.addConstraint(ExplicitSelectorGraph.forbidPair("n" + parent, "fed-lout", "n" + i,
						"fed-fout"));
			}
		}
		return builder.build();
	}

	private static Node ordinaryNode(String id) {
		return Node.of(id, local("local"), fedLout("fed-lout"), fedFout("fed-fout"));
	}

	private static Choice local(String id) {
		return Choice.of(id, Execution.CP, Output.LOUT);
	}

	private static Choice fedLout(String id, String... relocations) {
		return Choice.of(id, Execution.FED, Output.LOUT, relocations);
	}

	private static Choice fedFout(String id, String... relocations) {
		return Choice.of(id, Execution.FED, Output.FOUT, relocations);
	}
}

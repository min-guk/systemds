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

package org.apache.sysds.test.component.federated.placement.oracle.builder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Constraint;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.ConstraintKind;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Exec;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Graph;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Node;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Output;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Placement;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Reason;

public class BuilderOracleTest {
	@Test
	public void fixtureCorpusIsExactlyB01ThroughB22() {
		Assert.assertEquals(Arrays.asList(
			"B-01", "B-02", "B-03", "B-04", "B-05", "B-06", "B-07", "B-08", "B-09", "B-10", "B-11",
			"B-12", "B-13", "B-14", "B-15", "B-16", "B-17", "B-18", "B-19", "B-20", "B-21", "B-22"),
			BuilderOracleFixtures.ids());
	}

	@Test public void testB01SequentialWrites() { verify("B-01"); }
	@Test public void testB02EqualBranchWrites() { verify("B-02"); }
	@Test public void testB03DivergentBranchWrites() { verify("B-03"); }
	@Test public void testB04ConstantBranch() { verify("B-04"); }
	@Test public void testB05LongLoopClosure() { verify("B-05"); }
	@Test public void testB06LocalOverwrite() { verify("B-06"); }
	@Test public void testB07FunctionIo() { verify("B-07"); }
	@Test public void testB08DeadFunctionOutput() { verify("B-08"); }
	@Test public void testB09RecompileClone() { verify("B-09"); }
	@Test public void testB10SharedDag() { verify("B-10"); }
	@Test public void testB11AnchoredUpload() { verify("B-11"); }
	@Test public void testB12MissingAnchor() { verify("B-12"); }
	@Test public void testB13UnsupportedAnchor() { verify("B-13"); }
	@Test public void testB14Privacy() { verify("B-14"); }
	@Test public void testB15UnsupportedShape() { verify("B-15"); }
	@Test public void testB16TransientLegality() { verify("B-16"); }
	@Test public void testB17CallSiteIdentity() { verify("B-17"); }
	@Test public void testB18LoopPhi() { verify("B-18"); }
	@Test public void testB19NonConstantBranch() { verify("B-19"); }
	@Test public void testB20NestedBranchLoop() { verify("B-20"); }
	@Test public void testB21UnknownMetadata() { verify("B-21"); }
	@Test public void testB22SharedMaterialization() { verify("B-22"); }

	private static void verify(String id) {
		Graph graph = BuilderOracleFixtures.fixture(id);
		Assert.assertFalse(id + " must have a finite candidate universe", graph.nodes().isEmpty());
		Assert.assertFalse(id + " must have at least one bounded legal assignment", graph.legalAssignments().isEmpty());
		switch (id) {
			case "B-01":
				Assert.assertEquals("X#1", graph.node("write@10").valueVersion);
				Assert.assertEquals("X#2", graph.node("write@20").valueVersion);
				Assert.assertEquals("X#2", graph.node("read@30").valueVersion);
				Assert.assertEquals(2, count(graph, ConstraintKind.DOMINATES));
				break;
			case "B-02":
				Assert.assertEquals(set("FED/FOUT/ROW"), signatures(graph.node("join")));
				break;
			case "B-03":
				Assert.assertEquals(set("CP/LOUT", "FED/FOUT/ROW"), signatures(graph.node("join")));
				Assert.assertEquals(2, count(graph, ConstraintKind.CONJUNCTIVE));
				Assert.assertEquals(graph.normalizedCandidateUniverse(),
					BuilderOracleFixtures.divergentBranchWritesReversed().normalizedCandidateUniverse());
				break;
			case "B-04":
				Assert.assertTrue(graph.node("untaken").candidates().isEmpty());
				assertReason(graph.node("untaken"), Reason.UNREACHABLE_BRANCH);
				break;
			case "B-05":
				for (int i = 0; i < 7; i++) Assert.assertEquals(set("FED/FOUT/ROW"), signatures(graph.node("phi" + i)));
				break;
			case "B-06":
				Assert.assertEquals(set("CP/LOUT"), signatures(graph.node("entry")));
				Assert.assertEquals(set("CP/LOUT"), signatures(graph.node("backedge")));
				break;
			case "B-07":
				Assert.assertNotNull(graph.node("f.in"));
				Assert.assertNotNull(graph.node("f.out"));
				Assert.assertEquals("site#1", graph.node("f.read").contextId);
				break;
			case "B-08":
				Assert.assertFalse(graph.node("analysis-root").emittedWork);
				Assert.assertFalse(graph.node("dead-output").emittedWork);
				break;
			case "B-09":
				Assert.assertEquals("origin", graph.node("clone").originId);
				Assert.assertFalse(signatures(graph.node("clone")).contains("CP/FOUT/ROW"));
				assertReason(graph.node("clone"), Reason.RECOMPILE_CP_FOUT);
				break;
			case "B-10":
				Assert.assertEquals(3, graph.nodes().size());
				Assert.assertEquals(1, graph.nodes().stream().filter(n -> n.id.equals("shared")).count());
				break;
			case "B-11":
				Assert.assertTrue(signatures(graph.node("upload")).contains("CP/FOUT/ROW"));
				Assert.assertEquals("fed:X", graph.relocations().values().iterator().next().anchor);
				Assert.assertEquals(set("consumer#1"), graph.relocations().values().iterator().next().obligations());
				break;
			case "B-12":
				Assert.assertEquals(set("CP/LOUT"), signatures(graph.node("upload")));
				assertReason(graph.node("upload"), Reason.MISSING_ANCHOR);
				break;
			case "B-13":
				Assert.assertEquals(set("CP/LOUT"), signatures(graph.node("upload")));
				assertReason(graph.node("upload"), Reason.UNSUPPORTED_ANCHOR);
				break;
			case "B-14":
				Assert.assertEquals(set("CP/LOUT"), signatures(graph.node("private")));
				assertReason(graph.node("private"), Reason.PRIVACY);
				break;
			case "B-15":
				Assert.assertEquals(set("CP/LOUT"), signatures(graph.node("unsupported")));
				assertReason(graph.node("unsupported"), Reason.UNSUPPORTED_OPERATION_SHAPE);
				break;
			case "B-16":
				Assert.assertEquals(set("CP/LOUT", "FED/FOUT/ROW"), signatures(graph.node("read")));
				Assert.assertEquals(set("CP/LOUT", "FED/FOUT/ROW"), signatures(graph.node("write")));
				break;
			case "B-17":
				Assert.assertNotEquals(graph.node("call#1").contextId, graph.node("call#2").contextId);
				Assert.assertEquals(1, count(graph, ConstraintKind.DISTINCT_CONTEXT));
				break;
			case "B-18":
				Assert.assertEquals(4, count(graph, ConstraintKind.CONJUNCTIVE));
				Assert.assertEquals(set("CP/LOUT", "FED/FOUT/ROW"), signatures(graph.node("phi-local-fed")));
				Assert.assertEquals(set("CP/LOUT", "FED/FOUT/ROW"), signatures(graph.node("phi-fed-local")));
				break;
			case "B-19":
				Assert.assertEquals(2, count(graph, ConstraintKind.CONJUNCTIVE));
				Assert.assertEquals(set("CP/LOUT", "FED/FOUT/ROW"), signatures(graph.node("join")));
				break;
			case "B-20":
				Assert.assertEquals(4, count(graph, ConstraintKind.CONJUNCTIVE));
				Assert.assertNotNull(graph.node("loop-entry"));
				Assert.assertNotNull(graph.node("branch-join"));
				break;
			case "B-21":
				Assert.assertEquals(set("CP/LOUT"), signatures(graph.node("unknown")));
				assertReason(graph.node("unknown"), Reason.UNKNOWN_METADATA);
				break;
			case "B-22":
				Assert.assertEquals(1, graph.relocations().size());
				Assert.assertEquals(set("consumer#1", "consumer#2"),
					graph.relocations().values().iterator().next().obligations());
				break;
			default:
				Assert.fail("Unhandled fixture " + id);
		}
	}

	private static void assertReason(Node node, Reason reason) {
		Assert.assertTrue("Missing exclusion " + reason + " for " + node.id, node.exclusions().containsValue(reason));
	}

	private static int count(Graph graph, ConstraintKind kind) {
		int count = 0;
		for (Constraint constraint : graph.constraints()) if (constraint.kind == kind) count++;
		return count;
	}

	private static Set<String> signatures(Node node) {
		Set<String> out = new HashSet<>();
		for (Placement placement : node.candidates()) {
			Assert.assertNotNull(placement.exec);
			Assert.assertNotNull(placement.output);
			out.add(placement.signature());
		}
		return out;
	}

	private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }

	@Test
	public void transientPlacementsNeverContainForbiddenPairs() {
		Graph graph = BuilderOracleFixtures.fixture("B-16");
		for (String id : Arrays.asList("read", "write")) {
			for (Placement placement : graph.node(id).candidates()) {
				Assert.assertTrue((placement.exec == Exec.CP && placement.output == Output.LOUT)
					|| (placement.exec == Exec.FED && placement.output == Output.FOUT));
			}
		}
	}
}

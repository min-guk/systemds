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

package org.apache.sysds.test.component.federated.placement.oracle.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Exec;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.InputBinding;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Output;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.SemanticIdentity;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitiveChoice.Transfer;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.PrimitivePlanEnumerator.Range;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.SemanticCell.Anchor;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.SemanticCell.NodeSpec;

public class PrimitivePlanEnumeratorTest {
	private static final JointPlanLegalityChecker CHECKER = new JointPlanLegalityChecker();
	private static final PrimitiveChoice X_LOCAL = choice("x", Exec.CP, Output.LOUT, null, null);
	private static final PrimitiveChoice X_FED = choice("x", Exec.FED, Output.FOUT, "row:w1", "a");
	private static final PrimitiveChoice Y_LOCAL_DIRECT = choice("y", Exec.CP, Output.LOUT, null, null,
		bind("x", Output.LOUT, Transfer.DIRECT, null, null));
	private static final PrimitiveChoice Y_LOCAL_DOWNLOAD = choice("y", Exec.CP, Output.LOUT, null, null,
		bind("x", Output.LOUT, Transfer.DOWNLOAD, "download-x", "a"));
	private static final PrimitiveChoice Y_FED_DIRECT = choice("y", Exec.FED, Output.FOUT, "row:w1", "a",
		bind("x", Output.FOUT, Transfer.DIRECT, null, null));
	private static final PrimitiveChoice Y_FED_UPLOAD = choice("y", Exec.FED, Output.FOUT, "row:w1", "a",
		bind("x", Output.FOUT, Transfer.UPLOAD, "upload-x", "a"));

	@Test public void fullCartesianProductMatchesFourLiteralJointPlans() {
		SemanticCell cell = cell(false);
		List<List<PrimitiveChoice>> domains = List.of(List.of(X_LOCAL, X_FED),
			List.of(Y_LOCAL_DIRECT, Y_LOCAL_DOWNLOAD, Y_FED_DIRECT, Y_FED_UPLOAD));
		Set<JointPlan> legal = new HashSet<>();
		int[] counts = new int[3];
		PrimitivePlanEnumerator.enumerate(domains, new Range(BigInteger.ZERO, BigInteger.valueOf(8)), plan -> {
			JointPlanLegalityChecker.Status status = CHECKER.check(cell, plan).status();
			counts[status.ordinal()]++;
			if (status == JointPlanLegalityChecker.Status.LEGAL) legal.add(plan);
		});
		Set<JointPlan> literal = Set.of(
			new JointPlan(List.of(X_LOCAL, Y_LOCAL_DIRECT)),
			new JointPlan(List.of(X_LOCAL, Y_FED_UPLOAD)),
			new JointPlan(List.of(X_FED, Y_LOCAL_DOWNLOAD)),
			new JointPlan(List.of(X_FED, Y_FED_DIRECT)));
		Assert.assertEquals(literal, legal);
		Assert.assertArrayEquals(new int[] {4, 4, 0}, counts);
	}

	@Test public void restrictedAndEmptyLiteralCases() {
		SemanticCell cell = cell(true);
		Assert.assertEquals(JointPlanLegalityChecker.Status.LEGAL,
			CHECKER.check(cell, new JointPlan(List.of(X_FED, Y_FED_DIRECT))).status());
		Assert.assertEquals(JointPlanLegalityChecker.Status.ILLEGAL,
			CHECKER.check(cell, new JointPlan(List.of(X_FED, Y_LOCAL_DOWNLOAD))).status());
		Assert.assertEquals(JointPlanLegalityChecker.Status.ILLEGAL,
			CHECKER.check(cell, new JointPlan(List.of(X_LOCAL, Y_LOCAL_DIRECT))).status());
		Set<JointPlan> none = new HashSet<>();
		PrimitivePlanEnumerator.enumerate(List.of(List.of(X_LOCAL), List.of(Y_LOCAL_DIRECT)),
			new Range(BigInteger.ZERO, BigInteger.ONE), plan -> {
				if (CHECKER.check(cell, plan).status() == JointPlanLegalityChecker.Status.LEGAL) none.add(plan);
			});
		Assert.assertTrue(none.isEmpty());
	}

	@Test public void identityAndMutantsCannotHideMissingExtraBindingOrAuthority() {
		JointPlan expected = new JointPlan(List.of(X_LOCAL, Y_FED_UPLOAD));
		JointPlan reversed = new JointPlan(List.of(Y_FED_UPLOAD, X_LOCAL));
		Assert.assertEquals(expected, reversed);
		PrimitiveChoice otherAction = choice("y", Exec.FED, Output.FOUT, "row:w1", "a",
			bind("x", Output.FOUT, Transfer.UPLOAD, "other-action", "a"));
		PrimitiveChoice otherAuthority = choice("y", Exec.FED, Output.FOUT, "row:w1", "b",
			bind("x", Output.FOUT, Transfer.UPLOAD, "upload-x", "b"));
		PrimitiveChoice switchedBinding = choice("y", Exec.FED, Output.FOUT, "row:w1", "a",
			bind("other", Output.FOUT, Transfer.UPLOAD, "upload-x", "a"));
		Assert.assertNotEquals(expected, new JointPlan(List.of(X_LOCAL, otherAction)));
		Assert.assertNotEquals(expected, new JointPlan(List.of(X_LOCAL, otherAuthority)));
		Assert.assertNotEquals(expected, new JointPlan(List.of(X_LOCAL, switchedBinding)));
		Assert.assertEquals("S-BIND", CHECKER.check(cell(false),
			new JointPlan(List.of(X_LOCAL, switchedBinding))).ruleId());
		Assert.assertEquals("S-ANCHOR", CHECKER.check(cell(false),
			new JointPlan(List.of(X_LOCAL, otherAuthority))).ruleId());
		Set<JointPlan> reference = Set.of(expected);
		Set<JointPlan> removed = Set.of();
		Set<JointPlan> inserted = Set.of(new JointPlan(List.of(X_LOCAL, otherAuthority)));
		Assert.assertEquals(reference, difference(reference, removed));
		Assert.assertEquals(inserted, difference(inserted, reference));
	}

	@Test public void fullIdentitySeparatesLayoutTypeOccurrenceVersionAndContext() {
		PrimitiveChoice base = new PrimitiveChoice("x", Exec.FED, Output.FOUT, "row:w1", "a", List.of(),
			new SemanticIdentity("DURABLE_MAP", "ROW", "hop@1", "X#1", "main"));
		Assert.assertTrue(new JointPlan(List.of(base)).hasCompleteIdentity());
		Assert.assertFalse(new JointPlan(List.of(X_FED)).hasCompleteIdentity());
		SemanticCell cell = new SemanticCell("identity", List.of(
			new NodeSpec("x", "SOURCE", "MATRIX", List.of(), false, false)),
			List.of(new Anchor("a", "row:w1")));
		Assert.assertEquals(JointPlanLegalityChecker.Status.UNKNOWN,
			CHECKER.checkForCertification(cell, new JointPlan(List.of(X_FED))).status());
		Assert.assertEquals("S-COVERAGE",
			CHECKER.checkForCertification(cell, new JointPlan(List.of(base))).ruleId());
		List<SemanticIdentity> changed = List.of(
			new SemanticIdentity("NATIVE_LINEAGE", "ROW", "hop@1", "X#1", "main"),
			new SemanticIdentity("DURABLE_MAP", "COL", "hop@1", "X#1", "main"),
			new SemanticIdentity("DURABLE_MAP", "ROW", "hop@2", "X#1", "main"),
			new SemanticIdentity("DURABLE_MAP", "ROW", "hop@1", "X#2", "main"),
			new SemanticIdentity("DURABLE_MAP", "ROW", "hop@1", "X#1", "call@2"));
		for (SemanticIdentity identity : changed)
			Assert.assertNotEquals(new JointPlan(List.of(base)), new JointPlan(List.of(
				new PrimitiveChoice("x", Exec.FED, Output.FOUT, "row:w1", "a", List.of(), identity))));
	}

	@Test public void unknownTuplesAreNeverCountedAsIllegalOrLegal() {
		SemanticCell unknown = new SemanticCell("unknown",
			List.of(new NodeSpec("x", "MATRIX_MULTIPLY", "MATRIX", List.of(), false, false)),
			List.of(new Anchor("a", "row:w1")));
		Assert.assertEquals(JointPlanLegalityChecker.Status.UNKNOWN,
			CHECKER.check(unknown, new JointPlan(List.of(X_FED))).status());
		SemanticCell unknownShape = new SemanticCell("unknown-shape",
			List.of(new NodeSpec("x", "SOURCE", "TENSOR", List.of(), false, false)), List.of());
		Assert.assertEquals(JointPlanLegalityChecker.Status.UNKNOWN,
			CHECKER.check(unknownShape, new JointPlan(List.of(X_LOCAL))).status());
	}

	@Test public void sharedActionCannotReferToDifferentProducers() {
		SemanticCell cell = new SemanticCell("shared-action", List.of(
			new NodeSpec("x", "SOURCE", "MATRIX", List.of(), false, false),
			new NodeSpec("q", "SOURCE", "MATRIX", List.of(), false, false),
			new NodeSpec("y", "COPY", "MATRIX", List.of("x"), false, false),
			new NodeSpec("z", "COPY", "MATRIX", List.of("q"), false, false)),
			List.of(new Anchor("a", "row:w1")));
		JointPlan bad = new JointPlan(List.of(X_LOCAL,
			choice("q", Exec.CP, Output.LOUT, null, null),
			choice("y", Exec.FED, Output.FOUT, "row:w1", "a",
				bind("x", Output.FOUT, Transfer.UPLOAD, "shared", "a")),
			choice("z", Exec.FED, Output.FOUT, "row:w1", "a",
				bind("q", Output.FOUT, Transfer.UPLOAD, "shared", "a"))));
		Assert.assertEquals("S-SHARED-ACTION", CHECKER.check(cell, bad).ruleId());
	}

	@Test public void transientAndRecompileRestrictionsAreExplicit() {
		SemanticCell transientCell = new SemanticCell("transient",
			List.of(new NodeSpec("t", "TREAD", "MATRIX", List.of(), false, false)),
			List.of(new Anchor("a", "row:w1")));
		Assert.assertEquals("S-TRANSIENT", CHECKER.check(transientCell,
			new JointPlan(List.of(choice("t", Exec.CP, Output.FOUT, "row:w1", "a")))).ruleId());
		Assert.assertEquals(JointPlanLegalityChecker.Status.LEGAL, CHECKER.check(transientCell,
			new JointPlan(List.of(choice("t", Exec.FED, Output.FOUT, "row:w1", "a")))).status());
		SemanticCell recompile = new SemanticCell("recompile",
			List.of(new NodeSpec("x", "SOURCE", "MATRIX", List.of(), false, true)),
			List.of(new Anchor("a", "row:w1")));
		Assert.assertEquals("S-RECOMPILE", CHECKER.check(recompile,
			new JointPlan(List.of(choice("x", Exec.CP, Output.FOUT, "row:w1", "a")))).ruleId());
	}

	@Test public void ungroundedNativeCycleIsUnknown() {
		SemanticCell cycle = new SemanticCell("cycle", List.of(
			new NodeSpec("x", "COPY", "MATRIX", List.of("y"), false, false),
			new NodeSpec("y", "COPY", "MATRIX", List.of("x"), false, false)),
			List.of(new Anchor("a", "row:w1")));
		JointPlan plan = new JointPlan(List.of(
			choice("x", Exec.FED, Output.FOUT, "row:w1", "a",
				bind("y", Output.FOUT, Transfer.DIRECT, null, null)),
			choice("y", Exec.FED, Output.FOUT, "row:w1", "a",
				bind("x", Output.FOUT, Transfer.DIRECT, null, null))));
		Assert.assertEquals("S-CYCLE", CHECKER.check(cycle, plan).ruleId());
	}

	@Test public void splitRangesCoverExactlyOnceAndRejectCorruption() {
		List<List<PrimitiveChoice>> domains = List.of(List.of(X_LOCAL, X_FED),
			List.of(Y_LOCAL_DIRECT, Y_FED_DIRECT));
		List<Range> ranges = List.of(new Range(BigInteger.ZERO, BigInteger.valueOf(2)),
			new Range(BigInteger.valueOf(2), BigInteger.valueOf(4)));
		PrimitivePlanEnumerator.checkPartition(PrimitivePlanEnumerator.size(domains), ranges);
		List<JointPlan> full = new ArrayList<>();
		PrimitivePlanEnumerator.enumerate(domains, new Range(BigInteger.ZERO, BigInteger.valueOf(4)), full::add);
		List<JointPlan> split = new ArrayList<>();
		for (Range range : ranges) PrimitivePlanEnumerator.enumerate(domains, range, split::add);
		Assert.assertEquals(full, split);
		Assert.assertEquals(4, new HashSet<>(split).size());
		assertBadPartition(BigInteger.valueOf(4), List.of(new Range(BigInteger.ZERO, BigInteger.ONE),
			new Range(BigInteger.valueOf(2), BigInteger.valueOf(4))));
		assertBadPartition(BigInteger.valueOf(4), List.of(new Range(BigInteger.ZERO, BigInteger.valueOf(3)),
			new Range(BigInteger.valueOf(2), BigInteger.valueOf(4))));
		assertBadPartition(BigInteger.valueOf(4), List.of(ranges.get(0)));
		List<List<PrimitiveChoice>> huge = new ArrayList<>();
		for (int i = 0; i < 70; i++) huge.add(List.of(X_LOCAL, X_FED));
		Assert.assertEquals(BigInteger.ONE.shiftLeft(70), PrimitivePlanEnumerator.size(huge));
	}

	@Test public void independentDirectDomainUsesBothLiteralAnchors() {
		SemanticCell cell = new SemanticCell("two-anchors", List.of(
			new NodeSpec("x", "SOURCE", "MATRIX", List.of(), false, false),
			new NodeSpec("y", "ELEMENTWISE", "MATRIX", List.of("x"), false, false)),
			List.of(new Anchor("a", "row:w1"), new Anchor("b", "col:w2")));
		PrimitiveDomainGenerator.Result generated = new PrimitiveDomainGenerator().generate(cell);
		Assert.assertEquals(PrimitiveDomainGenerator.Coverage.DIRECT_ONLY, generated.coverage());
		Assert.assertTrue(generated.limitation().contains("UNKNOWN"));
		Assert.assertEquals(BigInteger.valueOf(9), PrimitivePlanEnumerator.size(generated.domains()));
		Set<JointPlan> actual = new HashSet<>();
		PrimitivePlanEnumerator.enumerate(generated.domains(),
			new Range(BigInteger.ZERO, BigInteger.valueOf(9)), plan -> {
				if (CHECKER.check(cell, plan).status() == JointPlanLegalityChecker.Status.LEGAL) actual.add(plan);
			});
		Set<JointPlan> literal = Set.of(
			new JointPlan(List.of(X_LOCAL, choice("y", Exec.CP, Output.LOUT, null, null,
				bind("x", Output.LOUT, Transfer.DIRECT, null, null)))),
			new JointPlan(List.of(X_FED, choice("y", Exec.FED, Output.FOUT, "row:w1", "a",
				bind("x", Output.FOUT, Transfer.DIRECT, null, null)))),
			new JointPlan(List.of(choice("x", Exec.FED, Output.FOUT, "col:w2", "b"),
				choice("y", Exec.FED, Output.FOUT, "col:w2", "b",
					bind("x", Output.FOUT, Transfer.DIRECT, null, null)))));
		Assert.assertEquals(literal, actual);
	}

	@Test public void fixedSourceFederationMapDoesNotBecomeAnotherAnchor() {
		SemanticCell cell = new SemanticCell("fixed-source", List.of(
			new NodeSpec("x", "SOURCE", "MATRIX", List.of(), true, false, "a")),
			List.of(new Anchor("a", "row:w1"), new Anchor("b", "col:w2")));
		PrimitiveDomainGenerator.Result generated = new PrimitiveDomainGenerator().generate(cell);
		Assert.assertEquals(2, generated.domains().get(0).size());
		Assert.assertEquals("a", generated.domains().get(0).get(1).authority());
		PrimitiveChoice forged = choice("x", Exec.FED, Output.FOUT, "col:w2", "b");
		Assert.assertEquals("S-SOURCE-MAP", CHECKER.check(cell,
			new JointPlan(List.of(forged))).ruleId());
	}

	private static void assertBadPartition(BigInteger total, List<Range> ranges) {
		try {
			PrimitivePlanEnumerator.checkPartition(total, ranges);
			Assert.fail("bad partition was accepted");
		}
		catch (IllegalArgumentException expected) { }
	}

	private static Set<JointPlan> difference(Set<JointPlan> left, Set<JointPlan> right) {
		Set<JointPlan> result = new HashSet<>(left);
		result.removeAll(right);
		return result;
	}

	private static SemanticCell cell(boolean restricted) {
		return new SemanticCell("two-node", List.of(
			new NodeSpec("x", "SOURCE", "MATRIX", List.of(), restricted, false),
			new NodeSpec("y", "COPY", "MATRIX", List.of("x"), restricted, false)),
			List.of(new Anchor("a", "row:w1")));
	}

	private static PrimitiveChoice choice(String node, Exec exec, Output output, String geometry,
		String authority, InputBinding... bindings) {
		return new PrimitiveChoice(node, exec, output, geometry, authority, Arrays.asList(bindings));
	}

	private static InputBinding bind(String producer, Output required, Transfer transfer,
		String action, String authority) {
		return new InputBinding(producer, required, transfer, action, authority);
	}
}

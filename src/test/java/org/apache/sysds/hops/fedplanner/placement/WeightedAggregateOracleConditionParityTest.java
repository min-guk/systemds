/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade.DecisionEvidence;
import org.junit.Assert;
import org.junit.Test;

/** Locks weighted aggregate execution rectangles to the exact forward Oracle. */
public class WeightedAggregateOracleConditionParityTest {
	private static final long SEED = 0x57_45_49_47_48_54_45_44L;

	@Test
	public void exhaustiveScalarWeightedConditionsMatchForwardOracle() {
		for(QuaternaryOp operation : operations())
			assertRelationParity(operation, fullDomains(), new ShapeHint(8, 4, 1_000));
	}

	@Test
	public void fixedSeedDomainSlicesPreserveEveryWeightedDecision() {
		Random random = new Random(SEED);
		List<FType> universe = new ArrayList<>();
		universe.add(null);
		universe.addAll(Arrays.asList(FType.values()));
		for(QuaternaryOp operation : operations())
			for(int sample = 0; sample < 64; sample++) {
				List<List<FType>> domains = new ArrayList<>();
				for(int position = 0; position < 4; position++) {
					List<FType> shuffled = new ArrayList<>(universe);
					Collections.shuffle(shuffled, random);
					domains.add(Collections.unmodifiableList(new ArrayList<>(
						shuffled.subList(0, 1 + random.nextInt(shuffled.size())))));
				}
				assertRelationParity(operation, domains,
					new ShapeHint(1 + random.nextInt(32), 1 + random.nextInt(16), 1_000));
			}
	}

	private static void assertRelationParity(QuaternaryOp operation, List<List<FType>> domains,
		ShapeHint hint) {
		OracleFacade.PreparedDecision forward = facade().prepareDecision(operation);
		var relation = facade().prepareDecision(operation).prepareExecutionRelation(domains).orElseThrow();
		enumerate(domains, 0, new ArrayList<>(), tuple -> {
			DecisionEvidence expected = forward.decideWithEvidence(tuple, hint);
			DecisionEvidence actual = relation.evidenceFor(tuple);
			assertCaps(expected.caps(), actual.caps());
			Assert.assertEquals(expected.shapeProof(), actual.shapeProof());
		});
	}

	private static void assertCaps(OpCaps expected, OpCaps actual) {
		Assert.assertEquals(expected.category(), actual.category());
		Assert.assertEquals(expected.opcode(), actual.opcode());
		Assert.assertEquals(expected.exec(), actual.exec());
		Assert.assertEquals(expected.placement(), actual.placement());
		Assert.assertEquals(expected.foutFType(), actual.foutFType());
		Assert.assertEquals(expected.reason(), actual.reason());
		Assert.assertEquals(expected.detail(), actual.detail());
		Assert.assertEquals(expected.notes().stream().map(note ->
			note.code() + ":" + note.message()).toList(), actual.notes().stream().map(note ->
				note.code() + ":" + note.message()).toList());
	}

	private static void enumerate(List<List<FType>> domains, int position, List<FType> tuple,
		java.util.function.Consumer<List<FType>> consumer) {
		if(position == domains.size()) {
			consumer.accept(Collections.unmodifiableList(new ArrayList<>(tuple)));
			return;
		}
		for(FType type : domains.get(position)) {
			tuple.add(type);
			enumerate(domains, position + 1, tuple, consumer);
			tuple.remove(tuple.size() - 1);
		}
	}

	private static List<List<FType>> fullDomains() {
		List<FType> values = new ArrayList<>();
		values.add(null);
		values.addAll(Arrays.asList(FType.values()));
		return List.of(values, values, values, values);
	}

	private static List<QuaternaryOp> operations() {
		return List.of(
			new QuaternaryOp("wsloss-condition-parity", DataType.SCALAR, ValueType.FP64,
				OpOp4.WSLOSS, matrix("X-loss"), matrix("U-loss"), matrix("V-loss"), matrix("W-loss"), false),
			new QuaternaryOp("wcemm-condition-parity", DataType.SCALAR, ValueType.FP64,
				OpOp4.WCEMM, matrix("X-ce"), matrix("U-ce"), matrix("V-ce"),
				new LiteralOp(0.1), 1, false, false));
	}

	private static Hop matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD, name, 8, 4, 32, 1_000);
	}

	private static OracleFacade facade() {
		return new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Compile-time RED for the exact canonical identity of the typed registration API. */
public class CampaignBG013TypedApiIdentityRedTest {
	private static final long SENTINEL_SCOPE = 9_015L;
	private static final long SENTINEL_HOP = 9_016L;
	private static final String MISSING_AUTHORITY = "Program has no authoritative placement analysis";
	private static final String FOREIGN_AUTHORITY = "Placement analysis is not the canonical program owner";

	@Test
	public void typedCallRequiresTheExactCanonicalAnalysisIdentity() {
		ArmedProgram program = program(new StatementBlock());
		PlacementAnalysis canonical = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		PlacementAnalysis foreign = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		program.installCanonicalTestAuthority(canonical);

		var receipt = FederatedRefedPolicy.registerFromProgram(program, Map.<Long, FType>of(), canonical);

		Assert.assertSame("G013_EXACT_ANALYSIS_RECEIPT", canonical, receipt.analysis());
		Assert.assertSame("G013_EXACT_TOP_LEVEL_RECEIPT", canonical.topLevelStatementBlocks(),
			receipt.topLevelStatementBlocks());
		program.assertTypedAuthorityInteraction("G013_TYPED_SUCCESS", canonical);
		program.resetAuthorityInteraction();
		seedPublicState();
		RegistryState before = snapshotPublicState();
		try {
			FederatedRefedPolicy.registerFromProgram(program, Map.<Long, FType>of(), foreign);
			Assert.fail("G013_FOREIGN_ANALYSIS_ACCEPTED");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals(FOREIGN_AUTHORITY, expected.getMessage());
			program.assertTypedAuthorityInteraction("G013_TYPED_FOREIGN", foreign);
		}
		finally {
			Assert.assertEquals("G013_FOREIGN_ANALYSIS_MUTATED_STATE", before, snapshotPublicState());
			clearPublicState();
		}
	}

	private static ArmedProgram program(StatementBlock... blocks) {
		ArmedProgram program = new ArmedProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(blocks)));
		return program;
	}

	private static void seedPublicState() {
		clearPublicState();
		FederatedPlannerUtils.registerFedAnchorKey("G013_TYPED_SENTINEL", "SIG:G013_TYPED|ROW");
		seedCpfoutAnchorCache();
		FederatedRefedRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP, SENTINEL_HOP + 1, "g013-anchor");
		FederatedFoutMaterializeRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP, SENTINEL_HOP + 1,
			FType.ROW.name(), "g013", "g013-anchor");
		FederatedLocalMaterializeRegistry.register(SENTINEL_SCOPE, SENTINEL_HOP, List.of(SENTINEL_HOP + 2),
			FType.ROW.name(), "g013-sentinel");
	}

	private static RegistryState snapshotPublicState() {
		return new RegistryState(FederatedRefedRegistry.snapshot(SENTINEL_SCOPE),
			FederatedFoutMaterializeRegistry.snapshot(SENTINEL_SCOPE),
			FederatedLocalMaterializeRegistry.snapshot(SENTINEL_SCOPE),
			FederatedPlannerUtils.snapshotFedAnchorKeys(), FederatedPlannerUtils.snapshotFedState(),
			FederatedRefedPolicy.snapshotCpfoutAnchorCache());
	}

	private static void clearPublicState() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedAnchorKeys();
		FederatedRefedPolicy.registerFromProgram((DMLProgram) null);
	}

	private static void seedCpfoutAnchorCache() {
		DataOp left = localMatrix("G013_TYPED_L");
		DataOp right = localMatrix("G013_TYPED_R");
		Hop target = HopRewriteUtils.createBinary(left, right, OpOp2.PLUS);
		target.setDim1(10);
		target.setDim2(10);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);
		DataOp anchor = federatedMatrix("G013_TYPED_A");
		BinaryOp parent = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		parent.setForcedExecType(ExecType.FED);
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(anchor.getHopID(), FType.ROW);
		FederatedRefedPolicy.registerFromHops(List.of(parent), true, fTypeMap, SENTINEL_SCOPE);
		FederatedPlannerUtils.removeFedInitVar("G013_TYPED_A");
		Assert.assertTrue("G013_TYPED_CPFOUT_CACHE_SEED_FAILED",
			FederatedRefedPolicy.snapshotCpfoutAnchorCache().containsKey(target.getHopID()));
	}

	private static DataOp localMatrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 10, 10, -1, 1000);
	}

	private static DataOp federatedMatrix(String name) {
		FederatedPlannerUtils.registerFedInitVar(name);
		DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 10, 10, -1, 1000);
		hop.setFederatedOutput(FederatedOutput.FOUT);
		hop.setForcedExecType(ExecType.FED);
		return hop;
	}

	private record RegistryState(Map<Long, FederatedRefedRegistry.AnchorSpec> refed,
		Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> fout,
		Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> local,
		Map<String, String> anchorKeys,
		Map<String, FederatedPlannerUtils.FedVarSnapshot> fedState,
		Map<Long, FederatedRefedPolicy.CpfoutAnchorSnapshot> cpfoutCache) {
	}

	/** Public-only fixture authority; no production state, reflection, or hidden hook is consulted. */
	private static final class ArmedProgram extends DMLProgram {
		private PlacementAnalysis canonical;
		private PlacementAnalysis validatedCandidate;
		private int retrievals;
		private int validations;

		private void installCanonicalTestAuthority(PlacementAnalysis analysis) {
			if(canonical != null)
				throw new IllegalStateException("G013_TEST_AUTHORITY_ALREADY_INSTALLED");
			canonical = analysis;
		}

		@Override
		public PlacementAnalysis requirePlacementAnalysisAuthority() {
			retrievals++;
			if(canonical == null)
				throw new IllegalStateException(MISSING_AUTHORITY);
			return canonical;
		}

		@Override
		public void requirePlacementAnalysisAuthority(PlacementAnalysis candidate) {
			validations++;
			validatedCandidate = candidate;
			if(candidate == null || canonical != candidate)
				throw new IllegalArgumentException(FOREIGN_AUTHORITY);
		}

		private void resetAuthorityInteraction() {
			validatedCandidate = null;
			retrievals = 0;
			validations = 0;
		}

		private void assertTypedAuthorityInteraction(String label, PlacementAnalysis expected) {
			Assert.assertSame(label + "_VALIDATED_IDENTITY", expected, validatedCandidate);
			Assert.assertEquals(label + "_UNEXPECTED_RETRIEVAL", 0, retrievals);
			Assert.assertEquals(label + "_VALIDATION_COUNT", 1, validations);
		}
	}
}

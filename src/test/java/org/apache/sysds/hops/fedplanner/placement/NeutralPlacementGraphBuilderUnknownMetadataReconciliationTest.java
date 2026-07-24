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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NeutralPlacementGraphBuilderUnknownMetadataReconciliationTest {
	private static final PlacementState ROW_FOUT =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, true);

	@Test
	public void legalInputDomainRemovesPriorInputSpecificUnknownMetadataForSameState() {
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState, Exclusion> excluded = new TreeMap<>();

		NeutralPlacementGraphBuilder.addUnknownMetadataExclusionUnlessProvenLegal(legal, excluded, ROW_FOUT,
			"inputs=0:null,1:ROW|missingRequiredFacts=[cols]");
		NeutralPlacementGraphBuilder.addLegalCandidate(legal, excluded, ROW_FOUT);

		Assert.assertTrue("state proven by a later input-domain must stay legal", legal.contains(ROW_FOUT));
		Assert.assertFalse("input-specific UNKNOWN_METADATA must not remain a global state exclusion",
			excluded.containsKey(ROW_FOUT));
	}

	@Test
	public void unknownMetadataAfterLegalInputDomainDoesNotBecomeGlobalExclusion() {
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState, Exclusion> excluded = new TreeMap<>();

		NeutralPlacementGraphBuilder.addLegalCandidate(legal, excluded, ROW_FOUT);
		NeutralPlacementGraphBuilder.addUnknownMetadataExclusionUnlessProvenLegal(legal, excluded, ROW_FOUT,
			"inputs=0:null,1:ROW|missingRequiredFacts=[cols]");

		Assert.assertTrue(legal.contains(ROW_FOUT));
		Assert.assertFalse(excluded.containsKey(ROW_FOUT));
	}

	@Test
	public void globalExclusionStillVetoesLegalCandidateForSameState() {
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState, Exclusion> excluded = new TreeMap<>();
		NeutralPlacementGraphBuilder.addGlobalExclusion(legal, excluded,
			new Exclusion(ROW_FOUT, ReasonCode.RECOMPILE_CP_FOUT, "global legality exclusion"));

		NeutralPlacementGraphBuilder.addLegalCandidate(legal, excluded, ROW_FOUT);

		Assert.assertFalse("global exclusions must not be erased by input-domain reconciliation",
			legal.contains(ROW_FOUT));
		Assert.assertEquals(ReasonCode.RECOMPILE_CP_FOUT, excluded.get(ROW_FOUT).reasonCode());
	}

	@Test
	public void laterGlobalExclusionRemovesPriorLegalCandidateForSameState() {
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState, Exclusion> excluded = new TreeMap<>();

		NeutralPlacementGraphBuilder.addLegalCandidate(legal, excluded, ROW_FOUT);
		NeutralPlacementGraphBuilder.addGlobalExclusion(legal, excluded,
			new Exclusion(ROW_FOUT, ReasonCode.ILLEGAL_TRANSIENT_PLACEMENT, "global legality exclusion"));

		Assert.assertFalse("global exclusions must remain authoritative even if discovered after legality",
			legal.contains(ROW_FOUT));
		Assert.assertEquals(ReasonCode.ILLEGAL_TRANSIENT_PLACEMENT, excluded.get(ROW_FOUT).reasonCode());
	}

	@Test
	public void laterGlobalExclusionReplacesPriorInputSpecificUnknownMetadata() {
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState, Exclusion> excluded = new TreeMap<>();

		NeutralPlacementGraphBuilder.addUnknownMetadataExclusionUnlessProvenLegal(legal, excluded, ROW_FOUT,
			"inputs=0:null,1:ROW|missingRequiredFacts=[cols]");
		NeutralPlacementGraphBuilder.addGlobalExclusion(legal, excluded,
			new Exclusion(ROW_FOUT, ReasonCode.RULE_ERROR, "global legality exclusion"));

		Assert.assertFalse(legal.contains(ROW_FOUT));
		Assert.assertEquals("later global exclusion must replace input-specific UNKNOWN_METADATA",
			ReasonCode.RULE_ERROR, excluded.get(ROW_FOUT).reasonCode());
	}

	@Test
	public void laterGlobalExclusionPreservesFirstAlreadyGlobalReason() {
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState, Exclusion> excluded = new TreeMap<>();

		NeutralPlacementGraphBuilder.addGlobalExclusion(legal, excluded,
			new Exclusion(ROW_FOUT, ReasonCode.RECOMPILE_CP_FOUT, "first global legality exclusion"));
		NeutralPlacementGraphBuilder.addGlobalExclusion(legal, excluded,
			new Exclusion(ROW_FOUT, ReasonCode.RULE_ERROR, "second global legality exclusion"));

		Assert.assertFalse(legal.contains(ROW_FOUT));
		Assert.assertEquals("first global exclusion must remain deterministic",
			ReasonCode.RECOMPILE_CP_FOUT, excluded.get(ROW_FOUT).reasonCode());
	}
}

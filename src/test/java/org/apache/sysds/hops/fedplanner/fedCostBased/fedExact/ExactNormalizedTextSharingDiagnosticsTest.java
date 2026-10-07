/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.AuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedTextBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ExactNormalizedTextSharingDiagnosticsTest {
	@Test
	public void productionDiagnosticIsDefaultOffAndPreservesTheFullCostCertificate() throws Exception {
		var analysis=ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model=ExactPhysicalModel.build(analysis);
		String property="sysds.fedplanner.liveMetrics";
		String previous=System.getProperty(property);
		PrintStream previousErr=System.err;
		ByteArrayOutputStream captured=new ByteArrayOutputStream();
		try {
			System.clearProperty(property);
			System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
			var unmeasured=ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			Assert.assertFalse(captured.toString(StandardCharsets.UTF_8).contains("SEARCH_SPACE_TEXT|"));
			captured.reset();
			System.setProperty(property, "true");
			var measured=ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			Assert.assertEquals(unmeasured.contributionFingerprint(), measured.contributionFingerprint());
			Assert.assertEquals(unmeasured.transferKeys(), measured.transferKeys());
			Assert.assertTrue(captured.toString(StandardCharsets.UTF_8)
				.contains("SEARCH_SPACE_TEXT|analysis="+analysis.analysisFingerprint()));
		}
		finally {
			System.setErr(previousErr);
			if(previous==null)
				System.clearProperty(property);
			else
				System.setProperty(property, previous);
		}
	}

	@Test
	public void sharedRopeMeasuresActualIdentityReuseWithoutChangingBytes() {
		NormalizedText child=new NormalizedTextBuilder().append("middle\ud800").build();
		NormalizedText shared=new NormalizedTextBuilder().append("prefix-").append(child)
			.append("-suffix").build();
		Alternative first=alternative("first", shared);
		Alternative second=alternative("second", shared);
		var diagnostics=new PhysicalSemanticDagFingerprint.NormalizedTextSharingDiagnostics();
		var measured=new PhysicalSemanticDagFingerprint(diagnostics);
		Assert.assertEquals(new PhysicalSemanticDagFingerprint().alternativeForTest(first),
			measured.alternativeForTest(first));
		Assert.assertEquals(new PhysicalSemanticDagFingerprint().alternativeForTest(second),
			measured.alternativeForTest(second));
		var snapshot=diagnostics.snapshotAndClear();
		long length=shared.length();
		Assert.assertEquals(2L, snapshot.normalizedOccurrences());
		Assert.assertEquals(1L, snapshot.distinctNormalizedIdentities());
		Assert.assertEquals(2L*length, snapshot.normalizedUnits());
		Assert.assertEquals(length, snapshot.uniqueNormalizedUnits());
		Assert.assertEquals(length, snapshot.repeatedNormalizedUnits());
		Assert.assertEquals(6L, snapshot.literalOccurrences());
		Assert.assertEquals(3L, snapshot.distinctLiteralIdentities());
		Assert.assertEquals(2L*length, snapshot.literalUnits());
		Assert.assertEquals(length, snapshot.uniqueLiteralUnits());
		Assert.assertEquals(length, snapshot.repeatedLiteralUnits());
		Assert.assertEquals(2L*length, snapshot.estimatedRetainedUtf16Bytes());
		Assert.assertEquals(2L*length, snapshot.estimatedAvoidedConversionBytes());
	}

	@Test
	public void equalDistinctTextIsNotReportedAsIdentityReuseAndClearReleasesState() {
		NormalizedText first=NormalizedText.literal(new String("same\ud800text"));
		NormalizedText second=NormalizedText.literal(new String("same\ud800text"));
		var diagnostics=new PhysicalSemanticDagFingerprint.NormalizedTextSharingDiagnostics();
		var measured=new PhysicalSemanticDagFingerprint(diagnostics);
		measured.alternativeForTest(alternative("first", first));
		measured.alternativeForTest(alternative("second", second));
		var snapshot=diagnostics.snapshotAndClear();
		Assert.assertEquals(2L, snapshot.distinctNormalizedIdentities());
		Assert.assertEquals(0L, snapshot.repeatedNormalizedUnits());
		Assert.assertEquals(2L, snapshot.distinctLiteralIdentities());
		Assert.assertEquals(0L, snapshot.repeatedLiteralUnits());
		var cleared=diagnostics.snapshotAndClear();
		Assert.assertEquals(0L, cleared.normalizedOccurrences());
		Assert.assertEquals(0L, cleared.distinctLiteralIdentities());
	}

	private static Alternative alternative(String suffix, NormalizedText signature) {
		PlacementState state=new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		return new Alternative(key(suffix), state, AuthorityKind.LEGAL_SINGLETON,
			null, null, null, null, null, null, null, List.of(), List.of(), null, null, signature);
	}

	private static CompiledHopKey key(String suffix) {
		ControlRegionKey region=new ControlRegionKey("program", "main", List.of("root"),
			"call", "compile");
		return new CompiledHopKey("program", "main", "call", "compile", region,
			"hop-"+suffix, "source-"+suffix);
	}
}

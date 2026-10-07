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

import java.lang.management.ManagementFactory;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.AuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthority;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class ExactSemanticRelocationTextMemoTest {
	@Test
	public void repeatedActionIdentityPreservesIndependentBytesWithoutRepeatedConstruction() {
		RelocationAction action=action("shared-"+"x".repeat(32*1024), "worker-a");
		Alternative repeated=alternative(List.copyOf(java.util.Collections.nCopies(256,
			authority(action))), action, "custom\ud800signature");
		String expected=IndependentPhysicalSemanticDagOracle.alternativeValue(repeated);
		Assert.assertEquals(expected,
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(repeated));

		java.lang.management.ThreadMXBean base=ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(base instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)base;
		Assume.assumeTrue(bean.isThreadAllocatedMemorySupported());
		boolean enabled=bean.isThreadAllocatedMemoryEnabled();
		try {
			if(!enabled)
				bean.setThreadAllocatedMemoryEnabled(true);
			for(int warmup=0; warmup<3; warmup++)
				ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(repeated);
			long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
			for(int repeat=0; repeat<3; repeat++)
				ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(repeated);
			long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
			System.out.println("SEMANTIC_RELOCATION_TEXT_ALLOCATED="+allocated);
			Assert.assertTrue("repeated immutable action text was reconstructed: "+allocated,
				allocated<3_000_000L);
		}
		finally {
			if(!enabled)
				bean.setThreadAllocatedMemoryEnabled(false);
		}
	}

	@Test
	public void equalForeignActionsRemainSemanticAndOccurrenceOrderRemainsBound() {
		RelocationAction first=action("equal", "worker-a");
		RelocationAction equalCopy=action("equal", "worker-a");
		Assert.assertNotSame(first, equalCopy);
		Assert.assertEquals(first, equalCopy);
		Alternative mixed=alternative(List.of(authority(first), authority(equalCopy), authority(first)),
			first, "caller-custom");
		Assert.assertEquals(IndependentPhysicalSemanticDagOracle.alternativeValue(mixed),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(mixed));
		Alternative equalIdentityOrder=alternative(
			List.of(authority(equalCopy), authority(first), authority(equalCopy)),
			first, "caller-custom");
		Assert.assertEquals("object identity must not enter the fingerprint",
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(mixed),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(equalIdentityOrder));

		RelocationAction differentDirectSource=action("equal", "worker-a", ExecType.CP);
		Alternative changed=alternative(List.of(authority(first), authority(differentDirectSource),
			authority(first)), first, "caller-custom");
		Assert.assertNotEquals("the complete action, including direct-source placement, remains bound",
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(mixed),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(changed));
		Alternative reordered=alternative(List.of(authority(differentDirectSource), authority(first),
			authority(first)), first, "caller-custom");
		Assert.assertNotEquals("input-authority occurrence order remains semantic",
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(changed),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(reordered));
	}

	private static Alternative alternative(List<InputAuthority> authorities, RelocationAction action,
		String signature) {
		CompiledHopKey decision=key("decision");
		PlacementState state=new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		return new Alternative(decision, state, AuthorityKind.LEGAL_SINGLETON, null, null, null, null,
			null, action, null, List.of(), authorities, null, null, NormalizedText.literal(signature));
	}

	private static InputAuthority authority(RelocationAction action) {
		return new InputAuthority(0, InputAuthorityKind.RELOCATION, FType.ROW,
			key("source"), action);
	}

	private static RelocationAction action(String scope, String worker) {
		return action(scope, worker, ExecType.FED);
	}

	private static RelocationAction action(String scope, String worker, ExecType directExec) {
		CompiledHopKey consumer=key("consumer");
		ControlRegionKey region=consumer.controlRegion();
		ValueVersionKey version=new ValueVersionKey("program", "value", region, 0,
			VersionKind.ORDINARY, List.of());
		PlacementState target=new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		DurableAnchorKey anchor=new DurableAnchorKey("anchor", FType.ROW,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(4L, 2L))));
		RelocationActionKey actionKey=new RelocationActionKey(version, target, anchor, scope,
			List.of(consumer));
		ObligationKey obligation=new ObligationKey(consumer, 0, version, target, actionKey, "context");
		PlacementState direct=new PlacementState(directExec, FederatedOutput.FOUT, FType.ROW, false);
		return new RelocationAction(actionKey, List.of(obligation), List.of(direct));
	}

	private static CompiledHopKey key(String suffix) {
		ControlRegionKey region=new ControlRegionKey("program", "main", List.of("root"),
			"call", "compile");
		return new CompiledHopKey("program", "main", "call", "compile", region,
			"hop-"+suffix, "source-"+suffix);
	}
}

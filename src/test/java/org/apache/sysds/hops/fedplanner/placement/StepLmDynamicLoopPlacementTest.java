/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** A protected dynamic column must survive both loop-carried cbind definitions. */
public class StepLmDynamicLoopPlacementTest {
	@Test
	public void dynamicColumnAndTwoLoopBackedgesKeepAnExecutableRowRead() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:19954/X1\",\"localhost:19955/X2\","
			+ "\"localhost:19956/X3\",\"localhost:19957/X4\"),"
			+ "ranges=list(list(0,0),list(2,3),list(2,0),list(4,3),"
			+ "list(4,0),list(6,3),list(6,0),list(8,3)));\n"
			+ "column_best=as.integer(sum(rand(rows=1,cols=1,min=1,max=3,seed=-1)));\n"
			+ "X_global=X[,column_best]; i=1;\n"
			+ "while(i<=3) {\n"
			+ " if(i==2) { X_global=cbind(X_global,X[,column_best]); }\n"
			+ " else { X_global=cbind(X_global,X[,column_best]); }\n"
			+ " i=i+1;\n"
			+ "}\n"
			+ "print(sum(X_global));\n";
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(script));
		PlacementAnalysis fullRecompute = new NeutralPlacementGraphBuilder(
			null, new SearchSpaceMetrics(), false).buildAnalysis(
				NeutralPlacementFixedPointCompositionTest.compileProtected(script));
		Assert.assertEquals("direct-closure cache may not alter the protected loop space",
			fullRecompute.analysisFingerprint(), analysis.analysisFingerprint());
		Assert.assertEquals(fullRecompute.candidateRuleFacts().orderedFacts(),
			analysis.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(fullRecompute.logicalTransientInputsInCanonicalOrder(),
			analysis.logicalTransientInputsInCanonicalOrder());
		List<PlacementAnalysis.HopOccurrenceProjection> reads = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "X_global".equals(data.getName()))
			.filter(occurrence -> analysis.cfgDefinitionSourcesInCanonicalOrder(occurrence.key()).size() >= 3)
			.toList();
		Assert.assertFalse("fixture must keep the entry and both loop definitions", reads.isEmpty());
		for(var read : reads) {
			Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(read.key()));
			Assert.assertTrue("all loop definitions must support a federated reader",
				analysis.graph().node(read.key()).orElseThrow().legalAlternatives().stream()
					.anyMatch(state -> state.execType() == ExecType.FED
						&& state.output() == FederatedOutput.FOUT && state.fType() == FType.ROW));
			List<CompiledHopKey> definitions = analysis.cfgDefinitionSourcesInCanonicalOrder(read.key());
			var relations = analysis.logicalTransientInputsForReader(read.key(), 0);
			Assert.assertEquals("every reaching definition retains an exact relation",
				definitions.size(), relations.size());
			Set<CompiledHopKey> sources = relations.stream()
				.map(PlacementAnalysis.LogicalTransientInputFact::sourceWrite).collect(Collectors.toSet());
			Assert.assertEquals(Set.copyOf(definitions), sources);
			Set<CandidateRealizationReference> commonReaders = null;
			for(var relation : relations) {
				Set<CandidateRealizationReference> readers = relation.compatibility().stream()
					.filter(edge -> edge.readerRealization().realization().emissionState()
						.placementState().execType() == ExecType.FED
						&& edge.readerRealization().realization().emissionState()
							.placementState().fType() == FType.ROW)
					.peek(edge -> {
						analysis.requireExactCandidateRealization(edge.sourceRealization());
						analysis.requireExactCandidateRealization(edge.readerRealization());
					}).map(PlacementAnalysis.TransientPlacementCompatibility::readerRealization)
					.collect(Collectors.toSet());
				if(commonReaders == null)
					commonReaders = readers;
				else
					commonReaders.retainAll(readers);
			}
			Assert.assertNotNull(commonReaders);
			Assert.assertFalse("one published ROW reader must be supported by all three definitions",
				commonReaders.isEmpty());
		}
	}
}

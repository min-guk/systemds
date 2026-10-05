/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.*;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.*;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.*;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.*;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class FullProfileIntegrationTest {
    @Test
    public void protectedParserFixtureCarriesFullProofToProfileAndAnchor() throws Exception {
        String script = "A=federated(addresses=list(\"localhost:1234/A\"),ranges=list(list(0,0),list(4,4)));\n"
            + "B=federated(addresses=list(\"localhost:1234/B\"),ranges=list(list(0,0),list(4,4)));\n"
            + "C=A%*%B;print(sum(C));\n";
        DMLProgram program = ParserFactory.createParser().parse(
            DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
        DMLTranslator translator = new DMLTranslator(program);
        translator.liveVariableAnalysis(program);
        translator.validateParseTree(program);
        translator.constructHops(program);
        Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for(StatementBlock block : program.getStatementBlocks())
            if(block.getHops() != null)
                for(Hop root : block.getHops()) protect(root, visited);
        PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
        int fullFacts = 0;
        for(var occurrence : analysis.compiledHopOccurrences()) {
            if(!(occurrence.hop() instanceof AggBinaryOp mm) || !mm.isMatrixMultiply()) continue;
            assertNotEquals(Privacy.PUBLIC, analysis.requirePrivacy(occurrence.key()));
            for(var fact : analysis.candidateRuleFacts().orderedFactsForParent(occurrence.key())) {
                var caps = fact.capability();
                if(caps == null || caps.nativeExec() != ExecType.FED
                    || caps.nativeOutput() != FederatedOutput.FOUT || caps.nativeFoutFType() != FType.FULL) continue;
                fullFacts++;
                assertTrue("actual candidate must retain its proved FULL profile",
                    fact.profile().producerOutputs().contains(FType.FULL));
            }
            assertTrue("same-shape single-worker MM must retain the proved anchor",
                analysis.graph().nodes().stream().filter(n -> n.key().equals(occurrence.key()))
                    .anyMatch(n -> n.anchors().stream().anyMatch(a -> a.fType() == FType.FULL)));
        }
        assertTrue(fullFacts > 0);
    }

    @Test
    public void anchorProofChecksEveryInputNotJustTheSelectedOutputAnchor() throws Exception {
        var single = anchor("a", List.of(new AnchorPartition("localhost:1234", List.of(0L,0L), List.of(4L,4L))));
        var split = anchor("z", List.of(
            new AnchorPartition("localhost:1234", List.of(0L,0L), List.of(2L,4L)),
            new AnchorPartition("localhost:1234", List.of(2L,0L), List.of(4L,4L))));
        assertNotNull(inherited(List.of(single, single)));
        assertNull("one worker can still host multiple ranges", inherited(List.of(single, split)));
        assertNull("unknown input ownership must not borrow the output proof", inherited(Arrays.asList(single, null)));
    }

    private static Object inherited(List<DurableAnchorKey> anchors) throws Exception {
        PlacementCandidateGenerator generator = new PlacementCandidateGenerator(
            new OracleFacade(RulesCore.RulesModule.createDefaultRegistry()), null);
        PlacementRelationClosure closure = new PlacementRelationClosure(generator, null, null, false, null, false);
        Hop a = new DataOp("A", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD, "A",4,4,16,1000);
        Hop b = new DataOp("B", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD, "B",4,4,16,1000);
        Hop mm = HopRewriteUtils.createMatrixMultiply(a,b);
        Method method = PlacementRelationClosure.class.getDeclaredMethod("inheritableDurableAnchor",
            Hop.class, String.class, NodeShapeFact.class, List.class, List.class);
        method.setAccessible(true);
        NodeShapeFact shape = new NodeShapeFact(DataType.MATRIX,4,4);
        return method.invoke(closure, mm, "protected-full-anchor", shape, List.of(shape,shape), anchors);
    }

    private static DurableAnchorKey anchor(String name, List<AnchorPartition> ranges) {
        return new DurableAnchorKey(name,FType.FULL,ranges);
    }

    private static void protect(Hop hop, Set<Hop> visited) {
        if(!visited.add(hop)) return;
        if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
            FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data,Privacy.PRIVATE_AGGREGATE);
        for(Hop input : hop.getInput()) protect(input,visited);
    }
}

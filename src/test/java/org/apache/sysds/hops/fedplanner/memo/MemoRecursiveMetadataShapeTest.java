/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoRecursiveMetadataShapeTest {
	@Test public void metadataDrivenLoopDimensionsShareTheGroundedHeadEvaluation() throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			"A=matrix(1,rows=1,cols=1);for(i in 1:3){"
				+"if(i==1){A=matrix(1,rows=2,cols=3);}"
				+"A=matrix(1,rows=nrow(A),cols=ncol(A));}print(sum(A));",new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		facts.requireComplete();
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		var occurrence=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof DataGenOp)
			.filter(value -> value.hop().getInput().stream().anyMatch(input -> input instanceof UnaryOp))
			.findFirst().orElseThrow();
		LoopDecision loop=new LoopDecision(0,List.of());
		EvaluationContext context=new EvaluationContext(Map.of(),Map.of(loop,LoopPhase.BACKEDGE),
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of(loop,IterationPhase.MIDDLE),
			Map.of(new LoopCardinalityDecision(0,List.of()),LoopCardinality.MANY));
		MemoShapes.Shape shape=shapes.shapeAt(occurrence.id(),context);
		Assert.assertTrue("metadata queries must reuse, not recursively reconstruct, the grounded loop head",shape.exact());
		Assert.assertEquals(2,shape.rows());
		Assert.assertEquals(3,shape.columns());
	}
	@Test public void nonInductiveMetadataDimensionsRemainUnknownWithoutRecursing() throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			"A=matrix(1,rows=1,cols=3);for(i in 1:3){"
				+"A=matrix(1,rows=nrow(A)+1,cols=ncol(A));}print(sum(A));",new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		facts.requireComplete();
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		var occurrence=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof DataGenOp)
			.filter(value -> value.context().contains("/loop-"))
			.findFirst().orElseThrow();
		LoopDecision loop=new LoopDecision(0,List.of());
		EvaluationContext context=new EvaluationContext(Map.of(),Map.of(loop,LoopPhase.BACKEDGE),
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of(loop,IterationPhase.MIDDLE),
			Map.of(new LoopCardinalityDecision(0,List.of()),LoopCardinality.MANY));
		Assert.assertFalse("a changing dimension is not a stationary loop-head proof",
			shapes.shapeAt(occurrence.id(),context).exact());
	}

}

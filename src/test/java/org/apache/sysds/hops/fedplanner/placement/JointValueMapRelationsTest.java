/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Relation;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class JointValueMapRelationsTest {
	@Test
	public void correlatedBranchRowsMayUseDifferentPools() throws Exception {
		PlacementJointInputAnalysis joint = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);B=matrix(2,2,2);}"
			+ "else{A=matrix(3,2,2);B=matrix(4,2,2);}C=A+B;print(sum(C));");
		Relation relation = relation(joint);
		Assert.assertEquals(2, relation.rows().size());
		Map<CompiledHopKey,DurableAnchorKey> pools = JointValueMapRelations.selectedPools();
		for(int row = 0; row < relation.rows().size(); row++)
			for(var input : relation.rows().get(row).inputs())
				pools.put(input.source(), pool("row-" + row, "worker-" + row));
		Assert.assertTrue(JointValueMapRelations.allRowsHaveAlignedPools(relation, pools));
	}

	@Test
	public void independentBranchesRejectCrossPoolRowsAndShareProducerSelections() throws Exception {
		PlacementJointInputAnalysis joint = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "q=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=matrix(1,2,2);}else{A=matrix(2,2,2);}"
			+ "if(q>0.5){B=matrix(3,2,2);}else{B=matrix(4,2,2);}C=A+B;print(sum(C));");
		Relation relation = relation(joint);
		Assert.assertEquals(4, relation.rows().size());
		Map<CompiledHopKey,DurableAnchorKey> pools = JointValueMapRelations.selectedPools();
		for(var row : relation.rows())
			for(var input : row.inputs())
				pools.putIfAbsent(input.source(), input.source().callSitePath().contains("branch-if")
					? pool("true", "worker-true") : pool("false", "worker-false"));
		Assert.assertFalse(JointValueMapRelations.allRowsHaveAlignedPools(relation, pools));
		long repeated = relation.sources().stream().filter(source -> relation.rows().stream()
			.filter(row -> row.inputs().stream().anyMatch(input -> input.source() == source)).count() > 1).count();
		Assert.assertTrue("fixture must reuse one producer decision across rows", repeated > 0);
	}

	private static Relation relation(PlacementJointInputAnalysis joint) {
		for(int ordinal = 0; ordinal < joint.occurrenceCount(); ordinal++) {
			var hop = joint.occurrenceHop(ordinal);
			if(hop.getInput().size() == 2 && hop.getInput().stream().allMatch(PlacementProgramFacts::isTransientRead)) {
				Relation relation = JointValueMapRelations.from(joint, joint.occurrenceKey(ordinal));
				if(relation != null)
					return relation;
			}
		}
		throw new AssertionError("No joint binary consumer");
	}

	private static PlacementJointInputAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return PlacementJointInputAnalysis.analyze(program);
	}

	private static DurableAnchorKey pool(String id, String worker) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(2L, 2L))));
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compile-only regression for the actual ADULT feature/label privacy split. */
public class LogregMixedPrivacyPublicationTest {
	private static final String SCRIPT =
		"X=federated(addresses=list(\"localhost:18101/X\"),"
			+ "ranges=list(list(0,0),list(50000,128)));\n"
			+ "Y=federated(addresses=list(\"localhost:18101/Y\"),"
			+ "ranges=list(list(0,0),list(50000,1)));\n"
			+ "Y=(Y<0)+1;\n"
			+ "m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,"
			+ "icpt=0,numclasses=2,numrows=50000,numcols=128);\n"
			+ "write(m,\"tmp/logreg-mixed-privacy-publication-test\",format=\"csv\");\n";

	@Test
	public void rewrittenFixtureMatchesAdultMixedSourcePrivacy() throws Exception {
		DMLProgram program = compileMixedPrivacy();
		assertMixedSourcePrivacy(program);
	}

	@Test
	public void protectedFeaturesAndPublicLabelsPublishWithinBoundedDiagnosticPasses()
		throws Exception {
		DMLProgram program = compileMixedPrivacy();
		assertMixedSourcePrivacy(program);

		AtomicInteger publicationPasses = new AtomicInteger();
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder(pass -> {
			if("publication".equals(pass.phase()) && publicationPasses.incrementAndGet() > 20)
				Assert.fail("mixed-privacy logreg publication exceeded the diagnostic 20-pass bound: "
					+ pass);
		});
		PlacementAnalysis analysis = builder.buildAnalysis(program);
		Assert.assertFalse(analysis.logicalTransientInputsInCanonicalOrder().isEmpty());
		Assert.assertTrue("publication observer must see the completed phase",
			publicationPasses.get() > 0 && publicationPasses.get() <= 20);
	}

	private static void assertMixedSourcePrivacy(DMLProgram program) {
		List<DataOp> sources = federatedSources(program);
		List<DataOp> features = sources.stream().filter(source -> "X".equals(source.getName())).toList();
		List<DataOp> labels = sources.stream().filter(source -> "Y".equals(source.getName())).toList();
		Assert.assertEquals("rewritten fixture must retain exactly the X and Y federated sources",
			sources.size(), features.size() + labels.size());
		Assert.assertEquals("rewritten fixture requires one feature source", 1, features.size());
		Assert.assertEquals("rewritten fixture requires one label source", 1, labels.size());
		Assert.assertEquals(128, features.get(0).getDim2());
		Assert.assertEquals(1, labels.get(0).getDim2());
		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE,
			FederatedPlannerUtils.resolveFederatedSourceMetadata(features.get(0)).privacy());
		Assert.assertEquals(Privacy.PUBLIC,
			FederatedPlannerUtils.resolveFederatedSourceMetadata(labels.get(0)).privacy());
	}

	private static DMLProgram compileMixedPrivacy() throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, SCRIPT, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		List<DataOp> sources = federatedSources(program);
		Assert.assertFalse("rewritten fixture must retain federated sources", sources.isEmpty());
		for(DataOp source : sources)
			if("X".equals(source.getName()))
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(
					source, Privacy.PRIVATE_AGGREGATE);
		return program;
	}

	private static List<DataOp> federatedSources(DMLProgram program) {
		Set<Hop> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		List<DataOp> sources = new ArrayList<>();
		for(PlacementGraphFingerprint.HopOccurrence occurrence :
			PlacementGraphFingerprint.orderedOccurrences(program)) {
			Hop hop = occurrence.hop();
			if(seen.add(hop) && hop instanceof DataOp source
				&& source.getOp() == OpOpData.FEDERATED)
				sources.add(source);
		}
		return sources;
	}
}

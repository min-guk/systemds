/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/** Native TRead metadata must preserve value origins through normalized nested branches. */
public class NativeAliasOriginProjectionTest {
	private static PlacementAnalysis analysis;
	private static List<CandidateRealizationReference> references;
	private static Method aliases;

	@BeforeClass
	public static void prepare() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){if(q>0.5){A=X;B=X;}else{A=Y;B=Y;}}else{A=X;B=X;}"
			+ "C=A+B;print(sum(C));";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		BranchPlacementNormalization.prepare(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		references = analysis.candidateRuleFacts().orderedFacts().stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream()
				.map(realization -> CandidateRealizationReference.of(fact.key(), realization)))).toList();
		aliases = JointValueMapRelations.class.getDeclaredMethod("aliasesOrigin", PlacementAnalysis.class,
			CandidateRealizationReference.class, CompiledHopKey.class, Set.class);
		aliases.setAccessible(true);
	}

	@Test
	public void nativeMetadataLeavesRetainExactReachingWriterOrigins() throws Exception {
		int checked = 0;
		for(var reference : references) {
			var hop = analysis.hop(reference.rule().parentOccurrence()).orElse(null);
			if(!PlacementProgramFacts.isTransientRead(hop) || pool(reference) == null
				|| analysis.requireExactCandidateRealization(reference).supportClauses().stream()
					.anyMatch(clause -> !clause.requiredInputSupport().isEmpty()))
				continue;
			for(var edge : analysis.transientCompatibilityForReader(reference)) {
				Assert.assertTrue("native read lost its exact reaching writer", aliases(reference,
					edge.sourceRealization().rule().parentOccurrence()));
				checked++;
			}
		}
		Assert.assertTrue("fixture must contain native reads with metadata-only authority", checked > 0);
	}

	@Test
	public void samePoolAndEqualLookingForeignKeysDoNotEstablishOrigins() throws Exception {
		int checked = 0;
		for(var reader : references) {
			var hop = analysis.hop(reader.rule().parentOccurrence()).orElse(null);
			if(!PlacementProgramFacts.isTransientRead(hop) || !"A".equals(hop.getName()) || pool(reader) == null)
				continue;
			for(var writer : references) {
				var source = analysis.hop(writer.rule().parentOccurrence()).orElse(null);
				if(!PlacementProgramFacts.isTransientWrite(source) || !"B".equals(source.getName())
					|| pool(writer) == null || !PlacementIdentity.samePhysicalWorkerPool(pool(reader), pool(writer)))
					continue;
				Assert.assertFalse("worker alignment is not value provenance",
					aliases(reader, writer.rule().parentOccurrence()));
				checked++;
			}
			for(var edge : analysis.transientCompatibilityForReader(reader)) {
				var owner = edge.sourceRealization().rule().parentOccurrence();
				var foreign = new CompiledHopKey(owner.programFingerprint(), owner.functionNamespace(),
					owner.callSitePath(), owner.recompileContext(), owner.controlRegion(),
					owner.emittedHopInstance(), owner.canonicalSourceOrigin());
				Assert.assertFalse("origin authority uses owned identity", aliases(reader, foreign));
			}
		}
		Assert.assertTrue("fixture must have unrelated writers on the same pool", checked > 0);
	}

	@Test
	public void computationDoesNotAliasItsOperand() throws Exception {
		int checked = 0;
		for(var reference : references) {
			var owner = reference.rule().parentOccurrence();
			if(!(analysis.hop(owner).orElse(null) instanceof BinaryOp))
				continue;
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder())
				if(edge.consumer() == owner) {
					Assert.assertFalse(aliases(reference, edge.producer()));
					checked++;
				}
		}
		Assert.assertTrue(checked > 0);
	}

	private static boolean aliases(CandidateRealizationReference reference, CompiledHopKey origin) throws Exception {
		return (boolean) aliases.invoke(null, analysis, reference, origin, new HashSet<>());
	}

	private static DurableAnchorKey pool(CandidateRealizationReference reference) {
		var realization = analysis.requireExactCandidateRealization(reference);
		return realization.supportClauses().stream().map(realization::provenWorkerPoolForOwnedClause)
			.filter(java.util.Objects::nonNull).findFirst().orElse(null);
	}
}

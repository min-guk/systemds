/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class P2EncodedClippingAuthorityTest {
	@Test
	public void encodedClippingHasCompleteProtectedPhysicalDomains() throws Exception {
		String old = System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		try {
			System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
			String script = """
				A=federated(addresses=list("localhost:8001/X1","localhost:8002/X2","localhost:8003/X3"),
				  ranges=list(list(0,0),list(4,3),list(4,0),list(8,3),list(8,0),list(12,3)));
				F=as.frame(A);
				[X0,M]=transformencode(target=F,spec="{ids:true,dummycode:[1],cofeePublicRecodeMetadata:true}");
				mu=colMeans(X0); sd=colSds(X0);
				mask=(X0<mu-1.5*sd)|(X0>mu+1.5*sd);
				X=X0-mask*X0+mask*mu;
				print(sum(X));write(M,"metadata");
				""";
			DMLProgram program = ParserFactory.createParser().parse(
				DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
			DMLTranslator translator = new DMLTranslator(program);
			translator.liveVariableAnalysis(program);
			translator.validateParseTree(program);
			translator.constructHops(program);
			translator.rewriteHopsDAG(program);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
			var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			var encodedNodes = analysis.graph().nodes().stream()
				.filter(node -> "X0".equals(node.valueVersion().lexicalVariable())).toList();
			Assert.assertFalse("encoded primary result must have exact graph occurrences", encodedNodes.isEmpty());
			for(var node : encodedNodes) {
				Assert.assertTrue("encoded output must not acquire raw frame range identity", node.anchors().isEmpty());
				Assert.assertEquals("encoded primary result must remain protected",
					Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(node.key()));
				Assert.assertTrue("encoded primary result cannot become coordinator-local",
					node.legalAlternatives().stream().allMatch(state ->
						state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT));
			}
			var model = ExactPhysicalModel.build(analysis);
			Assert.assertFalse(model.domains().isEmpty());
			for(var domain : model.domains())
				Assert.assertFalse("Every physical decision must have a realizable supply", domain.alternatives().isEmpty());
			var outer = model.domains().stream().filter(domain -> {
				var hop = analysis.hop(domain.node().key()).orElse(null);
				return hop instanceof BinaryOp binary && "X".equals(binary.getName());
			}).findFirst().orElseThrow();
			Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(outer.node().key()));
			Assert.assertTrue("protected clipping result cannot be released locally",
				outer.alternatives().stream().allMatch(alternative ->
					alternative.state().execType() == ExecType.FED
						&& alternative.state().output() == FederatedOutput.FOUT));
			Assert.assertTrue("outer clipping addition needs an exact ROW receipt for both matrix inputs",
				outer.alternatives().stream().allMatch(alternative ->
					alternative.inputAuthorities().stream()
						.filter(authority -> authority.expectedFType() == FType.ROW
							&& authority.sourceDecision() != null && authority.relocationAction() != null)
						.map(ExactPhysicalModel.InputAuthority::inputPosition)
						.collect(java.util.stream.Collectors.toSet()).equals(Set.of(0, 1))));
		}
		finally {
			if(old == null) System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
			else System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, old);
		}
	}
}

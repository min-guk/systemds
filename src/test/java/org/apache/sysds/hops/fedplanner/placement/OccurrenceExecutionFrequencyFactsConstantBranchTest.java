/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for proof-bounded constant branch execution weights. */
public class OccurrenceExecutionFrequencyFactsConstantBranchTest {
	@Test
	public void knownTrueAndFalsePredicatesSelectOnlyTheReachableArm() throws Exception {
		PlacementAnalysis analysis = analyze("""
			x=0;
			if(x==0) { true_arm=11; } else { false_arm=12; }
			if(x!=0) { false_arm_two=13; } else { true_arm_two=14; }
			print(true_arm + false_arm + false_arm_two + true_arm_two);
		""", false);

		assertWeight(analysis, "true_arm", 1.0);
		assertWeight(analysis, "false_arm", 0.0);
		assertWeight(analysis, "false_arm_two", 0.0);
		assertWeight(analysis, "true_arm_two", 1.0);
		Assert.assertTrue("Dead occurrences remain represented",
			analysis.executionFrequencyFacts().profilesByPath().containsKey(path(analysis, "false_arm")));
	}

	@Test
	public void unknownPredicateRetainsConfiguredExpectation() throws Exception {
		PlacementAnalysis analysis = analyze("""
			X=rand(rows=2,cols=2,seed=7);
			if(sum(X)>0) { unknown_if=1; } else { unknown_else=2; }
			print(unknown_if + unknown_else);
			""", true);
		assertWeight(analysis, "unknown_if", 0.5);
		assertWeight(analysis, "unknown_else", 0.5);
	}

	@Test
	public void branchJoinIncludesThePriorValueFromAnUnwrittenArm() throws Exception {
		PlacementAnalysis analysis = analyze("""
			X=rand(rows=2,cols=2,seed=7);
			x=0;
			if(sum(X)>0) { x=1; }
			if(x==0) { joined_if=1; } else { joined_else=2; }
			print(joined_if + joined_else + x);
			""", false);
		assertWeight(analysis, "joined_if", 0.5);
		assertWeight(analysis, "joined_else", 0.5);
	}

	@Test
	public void scalarAliasUsesItsDefinitionTimeEnvironment() throws Exception {
		PlacementAnalysis analysis = analyze("""
			X=rand(rows=2,cols=2,seed=7);
			x=0; y=x;
			if(sum(X)>0) { separator=1; } else { separator=2; }
			x=1;
			if(y==0) { alias_live=1; } else { alias_dead=2; }
			print(alias_live+alias_dead+separator);
			""", false);
		assertWeight(analysis, "alias_live", 1.0);
		assertWeight(analysis, "alias_dead", 0.0);
	}

	@Test
	public void loopWrittenPredicateIsNotSpecializedFromItsInitialValue() throws Exception {
		PlacementAnalysis analysis = analyze("""
			flag=0;
			for(i in 1:2) {
				if(flag==0) { loop_if=1; } else { loop_else=2; }
				flag=1;
			}
			print(flag + loop_if + loop_else);
			""", false);
		assertWeight(analysis, "loop_if", 1.0);
		assertWeight(analysis, "loop_else", 1.0);
	}

	@Test
	public void functionActualsRemainDistinctAcrossCallContexts() throws Exception {
		PlacementAnalysis analysis = analyze("""
			choose=function(int flag) return (int out) {
				call_if=0; call_else=0;
				if(flag==0) { call_if=1; } else { call_else=2; }
				out=flag+call_if+call_else;
			}
			a=choose(0);
			b=choose(1);
			print(a+b);
			""", false);
		assertContextWeights(analysis, "call_if", List.of(0.0, 1.0));
		assertContextWeights(analysis, "call_else", List.of(0.0, 1.0));
	}

	@Test
	public void nestedSameValueCallsRetainBothCallerContexts() throws Exception {
		PlacementAnalysis analysis = analyze("""
			h=function(int flag) return (int out) {
				nested_hit=0;
				if(flag==0) { nested_hit=1; }
				out=nested_hit;
			}
			g=function(int flag) return (int out) { out=h(flag); }
			x=0; a=g(x); b=g(x); print(a+b);
			""", false);
		assertContextWeights(analysis, "nested_hit", List.of(1.0, 1.0));
		Assert.assertEquals(2.0, analysis.executionFrequencyFacts()
			.executionWeight(occurrence(analysis, "nested_hit").key()), 0.0);
	}

	@Test
	public void localFormalShadowingUsesTheLatestLocalDefinition() throws Exception {
		PlacementAnalysis analysis = analyze("""
			shadow=function(int flag) return (int out) {
				flag=1;
				if(flag==1) { shadow_live=1; } else { shadow_dead=2; }
				out=shadow_live+shadow_dead;
			}
			flag=0; result=shadow(flag); print(result);
			""", false);
		assertWeight(analysis, "shadow_live", 1.0);
		assertWeight(analysis, "shadow_dead", 0.0);
	}

	@Test
	public void scalarActualExpressionIsFrozenBeforeCalleeShadowing() throws Exception {
		PlacementAnalysis analysis = analyze("""
			callee=function(int flag) return (int out) {
				x=100;
				if(flag==2) { expression_live=1; } else { expression_dead=2; }
				out=expression_live+expression_dead;
			}
			x=1; result=callee(x+1); print(result);
			""", false);
		assertWeight(analysis, "expression_live", 1.0);
		assertWeight(analysis, "expression_dead", 0.0);
	}

	@Test
	public void whileWrittenPredicateIsNotSpecializedFromItsInitialValue() throws Exception {
		PlacementAnalysis analysis = analyze("""
			flag=0; i=1;
			while(i<3) {
				if(flag==0) { while_if=1; } else { while_else=2; }
				flag=1; i=i+1;
			}
			print(while_if+while_else+flag);
			""", false);
		assertWeight(analysis, "while_if", 1.0);
		assertWeight(analysis, "while_else", 1.0);
	}

	@Test
	public void unknownFunctionOutputDoesNotBecomeALiteral() throws Exception {
		PlacementAnalysis analysis = analyze("""
			value=function(matrix[double] A) return (double out) { out=sum(A); }
			X=rand(rows=2,cols=2,seed=7);
			v=0;
			v=value(X);
			if(v==0) { output_if=1; } else { output_else=2; }
			print(output_if + output_else);
			""", false);
		assertWeight(analysis, "output_if", 0.5);
		assertWeight(analysis, "output_else", 0.5);
	}

	@Test
	public void loopFunctionOutputAndUnknownJoinKillPriorLiterals() throws Exception {
		PlacementAnalysis loop = analyze("""
			value=function(matrix[double] A) return (double out) { out=sum(A); }
			X=rand(rows=2,cols=2,seed=7); v=0;
			for(i in 1:2) { v=value(X); }
			if(v==0) { loop_output_if=1; } else { loop_output_else=2; }
			print(loop_output_if + loop_output_else);
			""", false);
		assertWeight(loop, "loop_output_if", 0.5);
		assertWeight(loop, "loop_output_else", 0.5);

		PlacementAnalysis join = analyze("""
			value=function(matrix[double] A) return (double out) { out=sum(A); }
			X=rand(rows=2,cols=2,seed=7); v=0;
			if(sum(X)>0) { v=value(X); } else { v=1; }
			if(v==1) { join_output_if=1; } else { join_output_else=2; }
			print(join_output_if + join_output_else);
			""", false);
		assertWeight(join, "join_output_if", 0.5);
		assertWeight(join, "join_output_else", 0.5);

		PlacementAnalysis nestedLoop = analyze("""
			X=rand(rows=2,cols=2,seed=7); v=0;
			for(i in 1:2) {
				v=v+i;
				if(sum(X)>0) { v=1; }
				if(v==1) { nested_loop_if=1; } else { nested_loop_else=2; }
			}
			print(nested_loop_if + nested_loop_else);
			""", false);
		assertWeight(nestedLoop, "nested_loop_if", 1.0);
		assertWeight(nestedLoop, "nested_loop_else", 1.0);
	}

	@Test
	public void scalarProofUsesRuntimeIntegerDivideModulusAndCastSemantics() throws Exception {
		PlacementAnalysis analysis = analyze("""
			q=(-5) %/% 2;
			if(q==-2) { intdiv_live=1; } else { intdiv_dead=2; }
			r=(-5) %% 2;
			if(r==-1) { modulus_live=3; } else { modulus_dead=4; }
			c=as.integer(1.9999999999999998);
			if(c==2) { cast_live=5; } else { cast_dead=6; }
			print(intdiv_live+intdiv_dead+modulus_live+modulus_dead+cast_live+cast_dead);
			""", false);
		assertWeight(analysis, "intdiv_live", 1.0);
		assertWeight(analysis, "intdiv_dead", 0.0);
		assertWeight(analysis, "modulus_live", 1.0);
		assertWeight(analysis, "modulus_dead", 0.0);
		assertWeight(analysis, "cast_live", 1.0);
		assertWeight(analysis, "cast_dead", 0.0);
	}

	@Test
	public void int64ArithmeticBeyondExactDoubleRangeRemainsUnknown() throws Exception {
		PlacementAnalysis analysis = analyze("""
			x=9007199254740992;
			y=x+1;
			if(y==9007199254740992) { wide_if=1; } else { wide_else=2; }
			print(wide_if+wide_else);
			""", false);
		assertWeight(analysis, "wide_if", 0.5);
		assertWeight(analysis, "wide_else", 0.5);
	}

	@Test
	public void stringComparisonRemainsUnknownInsteadOfNumericCoercion() throws Exception {
		PlacementAnalysis analysis = analyze("""
			x="01";
			if(x=="1") { string_if=1; } else { string_else=2; }
			print(string_if+string_else);
			""", false);
		assertWeight(analysis, "string_if", 0.5);
		assertWeight(analysis, "string_else", 0.5);
	}

	@Test
	public void glmShapedDeadStraightenXIsZeroWhileCgLoopRemainsLive() throws Exception {
		PlacementAnalysis analysis = analyze("""
			initialize=function(int dist_type, int link_type) return (int result) {
				if((dist_type==2) & (link_type>=2)) { desired_eta=0; }
				else { desired_eta=0.5; }
				straightenX_gram=0;
				if(desired_eta!=0) { straightenX_gram=17; }
				result=1+straightenX_gram;
			}
			initialized=initialize(2,2);
			for(cg_iter in 1:5) { live_cg=initialized+cg_iter; }
			print(live_cg + initialized);
			""", false);
		assertWeight(analysis, "straightenX_gram", 0.0);
		assertWeight(analysis, "live_cg", 5.0);
	}

	@Test
	public void actualBuiltinGlmDeadStraightenXIsZeroAndCgRemainsPositive() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(glmProgram());
		List<HopOccurrenceProjection> deadGram = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop().getBeginLine() == 1065
				&& "ba(+*)".equals(occurrence.hop().getOpString()))
			.toList();
		Assert.assertFalse("Actual glm.dml straightenX Gram occurrence must be retained", deadGram.isEmpty());
		for(HopOccurrenceProjection occurrence : deadGram)
			Assert.assertEquals("Actual dfam=2/link=2 straightenX Gram is unreachable", 0.0,
				analysis.executionFrequencyFacts().executionWeight(occurrence.key()), 0.0);

		List<HopOccurrenceProjection> liveCg = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop().getBeginLine() == 983
				&& "b(*)".equals(occurrence.hop().getOpString()))
			.toList();
		Assert.assertFalse("Actual glm.dml CG occurrence must be retained", liveCg.isEmpty());
		for(HopOccurrenceProjection occurrence : liveCg)
			Assert.assertTrue("Reachable CG work retains positive execution frequency",
				analysis.executionFrequencyFacts().executionWeight(occurrence.key()) > 0.0);
	}

	private static PlacementAnalysis analyze(String script, boolean rewrite) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite)
			translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static DMLProgram glmProgram() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\"),"
			+ "ranges=list(list(0,0),list(8,4)));\n"
			+ "Y=federated(addresses=list(\"localhost:1234/Y1\"),"
			+ "ranges=list(list(0,0),list(8,1)));\n"
			+ "Y=(Y>mean(Y))*1;\n"
			+ "beta=glm(X=X,Y=Y,dfam=2,vpow=0.0,link=2,lpow=1.0,yneg=0.0,"
			+ "icpt=0,disp=0.0,reg=0.0,tol=1e-6,moi=2,mii=2,verbose=FALSE);\n"
			+ "write(beta,\"/tmp/g014-constant-branch-glm\",format=\"csv\");\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static void assertWeight(PlacementAnalysis analysis, String writeName, double expected) {
		Assert.assertEquals(writeName, expected,
			analysis.executionFrequencyFacts().executionWeight(occurrence(analysis, writeName).key()), 0.0);
	}

	private static void assertContextWeights(PlacementAnalysis analysis, String writeName,
		List<Double> expected) {
		List<Double> actual = analysis.executionFrequencyFacts().profilesByPath()
			.get(path(analysis, writeName)).stream().map(OccurrenceProfileFact::expectedExecutions)
			.sorted().toList();
		Assert.assertEquals(writeName, expected, actual);
	}

	private static String path(PlacementAnalysis analysis, String writeName) {
		List<String> paths = occurrence(analysis, writeName).key().controlRegion().regionPath();
		Assert.assertEquals(writeName, 1, paths.size());
		return paths.get(0);
	}

	private static HopOccurrenceProjection occurrence(PlacementAnalysis analysis, String writeName) {
		List<HopOccurrenceProjection> matches = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTWRITE && writeName.equals(data.getName()))
			.toList();
		if(matches.size() > 1)
			matches = matches.stream().filter(occurrence -> occurrence.key().controlRegion()
				.regionPath().stream().anyMatch(path -> path.contains("/branch-"))).toList();
		Assert.assertEquals("Expected exactly one transient write for " + writeName, 1, matches.size());
		return matches.get(0);
	}
}

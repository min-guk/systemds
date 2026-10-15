/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.BoundaryPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class MemoBindingResolverTest {
	@Test
	public void equalCallFrameReusesTheImmutableEvaluationContext() {
		MemoBindingResolver.EvaluationContext context=
			MemoBindingResolver.EvaluationContext.empty().withCallFrame(List.of(4,9));

		Assert.assertSame("an equal immutable call frame must not allocate another context",context,
			context.withCallFrame(new ArrayList<>(List.of(4,9))));
	}

	@Test
	public void changedCallFramePreservesSemanticFieldsAndCopiesTheFrame() {
		MemoBindingResolver.GuardDecision guard=
			new MemoBindingResolver.GuardDecision(1,List.of());
		MemoBindingResolver.LoopDecision loop=
			new MemoBindingResolver.LoopDecision(2,List.of());
		MemoBindingResolver.LoopCardinalityDecision cardinality=
			new MemoBindingResolver.LoopCardinalityDecision(2,List.of());
		MemoBindingResolver.SelectionLookup selection=new MemoBindingResolver.SelectionLookup() { };
		MemoBindingResolver.EvaluationContext context=new MemoBindingResolver.EvaluationContext(
			Map.of(guard,true),Map.of(loop,MemoBindingResolver.LoopPhase.BACKEDGE),selection,17,List.of(4,9),
			Map.of(loop,MemoProgramFacts.IterationPhase.MIDDLE),
			Map.of(cardinality,MemoProgramFacts.LoopCardinality.MANY));
		List<Integer> requested=new ArrayList<>(List.of(7,8));

		MemoBindingResolver.EvaluationContext changed=context.withCallFrame(requested);
		requested.add(10);

		Assert.assertNotSame(context,changed);
		Assert.assertEquals(context.guards(),changed.guards());
		Assert.assertEquals(context.loops(),changed.loops());
		Assert.assertSame(selection,changed.selections());
		Assert.assertEquals(context.maxDepth(),changed.maxDepth());
		Assert.assertEquals(context.iterationPhases(),changed.iterationPhases());
		Assert.assertEquals(context.cardinalities(),changed.cardinalities());
		Assert.assertEquals(List.of(7,8),changed.callFrame());
	}

	@Test
	public void overwriteUsesOnlyTheExactReachingDefinition() {
		DMLProgram program = new DMLProgram();
		DataOp initialA = transientWrite("A", new LiteralOp(1L));
		DataOp firstRead = transientRead("A");
		DataOp writeU = transientWrite("U", firstRead);
		DataOp laterA = transientWrite("A", new LiteralOp(2L));
		DataOp secondRead = transientRead("A");
		DataOp writeV = transientWrite("V", secondRead);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(initialA, writeU, laterA, writeV)));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		List<MemoProgramFacts.Occurrence> reads = facts.occurrences().stream()
			.filter(o -> isTransientRead(o.hop()) && "A".equals(o.hop().getName())).toList();

		Set<Integer> first = resolver.influencingCompiledOwners(facts.valueBinding(reads.get(0).id()));
		Set<Integer> second = resolver.influencingCompiledOwners(facts.valueBinding(reads.get(1).id()));
		Assert.assertEquals(1, first.size());
		Assert.assertEquals(1, second.size());
		Assert.assertFalse(first.equals(second));
		Assert.assertEquals(second, resolver.directSupplyOwners(facts.valueBinding(reads.get(1).id())));
		int selectedOwner = second.iterator().next();
		List<MemoBindingResolver.ResolvedOrigin> origins = new ArrayList<>();
		MemoBindingResolver.SelectionLookup selection = new MemoBindingResolver.SelectionLookup() {
			@Override public int selectedRule(int owner) { return owner == selectedOwner ? 7 : -1; }
			@Override public MemoPartition selectedLayout(int owner) {
				return owner == selectedOwner ? MemoPartition.ROW : null;
			}
		};
		resolver.visitJoint(List.of(facts.valueBinding(reads.get(1).id())),
			new MemoBindingResolver.EvaluationContext(Map.of(), Map.of(), selection, 64), environment -> {
				origins.addAll(environment.origins());
				return true;
			});
		Assert.assertEquals(7, origins.get(0).selectedRule());
		Assert.assertEquals(MemoPartition.ROW, origins.get(0).selectedLayout());
	}

	@Test
	public void correlatedBranchHasTwoJointEnvironmentsRatherThanFour() throws Exception {
		MemoProgramFacts facts = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);B=matrix(2,2,2);}"
			+ "else{A=matrix(3,2,2);B=matrix(4,2,2);}C=A+B;print(sum(C));");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		int consumer = binaryTransientConsumer(facts, "A", "B");
		List<Integer> roots = List.of(facts.inputBinding(consumer, 0), facts.inputBinding(consumer, 1));
		List<MemoBindingResolver.ResolvedEnvironment> environments = new ArrayList<>();
		MemoBindingResolver.VisitResult result = resolver.visitJoint(roots,
			MemoBindingResolver.EvaluationContext.empty(), environment -> {
				environments.add(environment);
				return true;
			});

		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE, result.status());
		Assert.assertEquals(2, environments.size());
		for(MemoBindingResolver.ResolvedEnvironment environment : environments) {
			Assert.assertEquals(1, environment.guards().size());
			String left = facts.occurrence(environment.origins().get(0).referenceId()).context();
			String right = facts.occurrence(environment.origins().get(1).referenceId()).context();
			Assert.assertEquals(left.contains("/then"), right.contains("/then"));
			Assert.assertEquals(left.contains("/else"), right.contains("/else"));
		}
		MemoBindingResolver.OriginIdentity unknown=resolver.resolveOriginIdentity(roots.get(0),
			MemoBindingResolver.EvaluationContext.empty(),new MemoBindingResolver.ResolvedEnvironment(
				List.of(),Map.of(),Map.of(),Map.of()));
		Assert.assertEquals("identity resolution must not choose an unvalued guard arm",
			OriginKind.UNRESOLVED,unknown.kind());
	}

	@Test
	public void unrelatedDefinitionsAreOutsideTheDependencyScope() throws Exception {
		MemoProgramFacts facts = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){U=matrix(9,2,2);}else{U=matrix(8,2,2);}"
			+ "if(p>0.25){A=matrix(1,2,2);B=matrix(2,2,2);}"
			+ "else{A=matrix(3,2,2);B=matrix(4,2,2);}C=A+B;print(sum(C));print(sum(U));");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		int consumer = binaryTransientConsumer(facts, "A", "B");
		Set<Integer> relevant = resolver.influencingCompiledOwners(List.of(
			facts.inputBinding(consumer, 0), facts.inputBinding(consumer, 1)));
		Set<Integer> unrelated = new LinkedHashSet<>();
		facts.definitions().stream().filter(definition -> "U".equals(definition.variable()))
			.forEach(definition -> unrelated.addAll(
				resolver.influencingCompiledOwners(definition.sourceBindingId())));

		Assert.assertFalse(unrelated.isEmpty());
		Assert.assertTrue(CollectionsDisjoint.of(relevant, unrelated));
	}

	@Test
	public void inlinedCallsUseConcreteBodyOwnersAndDistinctActualDependencies() throws Exception {
		MemoProgramFacts facts = analyze("f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+ "P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=f(Q);C=A+B;print(sum(C));");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		int consumer = binaryTransientConsumer(facts, "A", "B");
		Set<Integer> first = resolver.directSupplyOwners(facts.inputBinding(consumer, 0));
		Set<Integer> second = resolver.directSupplyOwners(facts.inputBinding(consumer, 1));
		Assert.assertEquals(1, first.size());
		Assert.assertEquals(1, second.size());
		Assert.assertTrue("runtime inlined clones are distinct physical owners",
			CollectionsDisjoint.of(first, second));
		Set<Integer> summaryOwners = new LinkedHashSet<>(facts.functionSummary(0).compiledOwnerIds());
		Assert.assertTrue("uninvoked summary template must not replace emitted clone owners",
			CollectionsDisjoint.of(first, summaryOwners) && CollectionsDisjoint.of(second, summaryOwners));
		for(MemoProgramFacts.CallBinding call : facts.calls()) {
			int returned=call.returnBindingIds().get(0);
			resolver.visitJoint(List.of(returned),MemoBindingResolver.EvaluationContext.empty(),environment -> {
				MemoBindingResolver.OriginIdentity identity=resolver.resolveOriginIdentity(returned,
					MemoBindingResolver.EvaluationContext.empty(),environment);
				Assert.assertTrue("an inlined clone has no runtime call-export boundary",
					identity.exportChain().isEmpty());
				Assert.assertNotEquals(returned,identity.visibleBindingId());
				return true;
			});
		}

		int formal = facts.functionSummary(0).formalBindingIds().get(0);
		List<MemoBindingResolver.ResolvedOrigin> actuals = new ArrayList<>();
		for(int call = 0; call < 2; call++)
			resolver.visitJoint(List.of(formal), MemoBindingResolver.EvaluationContext.empty()
				.withCallFrame(List.of(call)), environment -> {
					actuals.addAll(environment.origins());
					return true;
				});
		Assert.assertEquals(2, actuals.size());
		Assert.assertTrue(actuals.get(0).compiledOwnerId() != actuals.get(1).compiledOwnerId());
		Assert.assertEquals(Set.of(actuals.get(0).compiledOwnerId()),
			resolver.directSupplyOwners(formal, List.of(0)));
		Assert.assertEquals(Set.of(actuals.get(1).compiledOwnerId()),
			resolver.directSupplyOwners(formal, List.of(1)));
	}

	@Test
	public void transparentFunctionReturnKeepsTheCallerSourceIdentity() throws Exception {
		DMLProgram program=parsedProgram(
			"f=function(matrix[double] X) return(matrix[double] Y){Y=X;}");
		FunctionOp callHop=new FunctionOp(FunctionType.DML,DMLProgram.DEFAULT_NAMESPACE,"f",
			new String[] {"X"},List.of(new LiteralOp(1L)),new String[] {"A"},true);
		StatementBlock calls=new StatementBlock(); calls.setHops(new ArrayList<>(List.of(callHop)));
		program.setStatementBlocks(new ArrayList<>(List.of(calls)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		int returned=facts.call(0).returnBindingIds().get(0);
		List<MemoBindingResolver.OriginIdentity> identities=new ArrayList<>();
		resolver.visitJoint(List.of(returned),MemoBindingResolver.EvaluationContext.empty(),environment -> {
			identities.add(resolver.resolveOriginIdentity(returned,
				MemoBindingResolver.EvaluationContext.empty(),environment)); return true;
		});
		Assert.assertEquals(1,identities.size());
		Assert.assertTrue("forwarding a caller value creates no new retained export",
			identities.get(0).exportChain().isEmpty());
		Assert.assertEquals(OriginKind.COMPILED_OWNER,identities.get(0).kind());
		Assert.assertNotEquals(returned,identities.get(0).visibleBindingId());
	}

	@Test
	public void nestedComputedReturnsExposeAnOrderedExportChain() throws Exception {
		DMLProgram program=parsedProgram(
			"f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
				+ "g=function(matrix[double] X) return(matrix[double] Y){Y=f(X);}");
		// Replace the compiler-inlined clone in g with an explicit compiled call.
		// An inlined clone has no runtime export boundary and is covered separately.
		FunctionStatementBlock gBlock=program.getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE,"g");
		FunctionStatement g=(FunctionStatement) gBlock.getStatement(0);
		FunctionOp nestedCall=new FunctionOp(FunctionType.DML,DMLProgram.DEFAULT_NAMESPACE,"f",
			new String[] {"X"},List.of(transientRead("X")),new String[] {"Y"},true);
		StatementBlock nestedBody=new StatementBlock();
		nestedBody.setHops(new ArrayList<>(List.of(nestedCall)));
		g.setBody(new ArrayList<>(List.of(nestedBody)));
		FunctionOp mainCall=new FunctionOp(FunctionType.DML,DMLProgram.DEFAULT_NAMESPACE,"g",
			new String[] {"X"},List.of(new LiteralOp(1L)),new String[] {"A"},true);
		StatementBlock calls=new StatementBlock(); calls.setHops(new ArrayList<>(List.of(mainCall)));
		program.setStatementBlocks(new ArrayList<>(List.of(calls)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoProgramFacts.CallBinding outer=facts.calls().stream()
			.filter(call -> call.callOccurrenceId()>=0
				&& facts.occurrence(call.callOccurrenceId()).hop()==mainCall)
			.findFirst().orElseThrow();
		int returned=outer.returnBindingIds().get(0);
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		List<MemoBindingResolver.OriginIdentity> identities=new ArrayList<>();
		resolver.visitJoint(List.of(returned),MemoBindingResolver.EvaluationContext.empty(),environment -> {
			identities.add(resolver.resolveOriginIdentity(returned,
				MemoBindingResolver.EvaluationContext.empty(),environment)); return true;
		});
		Assert.assertEquals(1,identities.size());
		MemoBindingResolver.OriginIdentity identity=identities.get(0);
		Assert.assertEquals(OriginKind.COMPILED_OWNER,identity.kind());
		Assert.assertEquals(2,identity.exportChain().size());
		Assert.assertEquals(identity.exportChain().get(0).targetBindingId(),
			identity.exportChain().get(1).sourceBindingId());
		Assert.assertEquals(returned,identity.visibleBindingId());
		Assert.assertEquals(outer.id(),identity.exportChain().get(1).callId());
	}

	@Test
	public void nonInlinedCallsShareTheCompiledBodyButRetainDistinctActualDependencies() throws Exception {
		DMLProgram program = parsedProgram(
			"f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}");
		FunctionOp firstCall = new FunctionOp(FunctionType.DML, DMLProgram.DEFAULT_NAMESPACE, "f",
			new String[] {"X"}, List.of(new LiteralOp(1L)), new String[] {"A"}, true);
		FunctionOp secondCall = new FunctionOp(FunctionType.DML, DMLProgram.DEFAULT_NAMESPACE, "f",
			new String[] {"X"}, List.of(new LiteralOp(2L)), new String[] {"B"}, true);
		StatementBlock calls = new StatementBlock();
		calls.setHops(new ArrayList<>(List.of(firstCall, secondCall)));
		program.setStatementBlocks(new ArrayList<>(List.of(calls)));

		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		Assert.assertEquals(2, facts.calls().size());
		Set<Integer> firstSupply = resolver.directSupplyOwners(facts.call(0).returnBindingIds().get(0));
		Set<Integer> secondSupply = resolver.directSupplyOwners(facts.call(1).returnBindingIds().get(0));
		Assert.assertEquals("a genuinely shared compiled body has one physical producer", firstSupply, secondSupply);
		Assert.assertEquals(1, firstSupply.size());
		List<MemoBindingResolver.OriginIdentity> identities=new ArrayList<>();
		for(MemoProgramFacts.CallBinding call : facts.calls()) {
			int returned=call.returnBindingIds().get(0);
			resolver.visitJoint(List.of(returned),MemoBindingResolver.EvaluationContext.empty(),environment -> {
				identities.add(resolver.resolveOriginIdentity(returned,
					MemoBindingResolver.EvaluationContext.empty(),environment));
				return true;
			});
		}
		Assert.assertEquals(2,identities.size());
		Assert.assertEquals(firstSupply.iterator().next().intValue(),identities.get(0).compiledOwnerId());
		Assert.assertEquals(identities.get(0).compiledOwnerId(),identities.get(1).compiledOwnerId());
		Assert.assertNotEquals("call return slots remain distinct retention identities",
			identities.get(0).visibleBindingId(),identities.get(1).visibleBindingId());
		Assert.assertEquals(facts.call(0).returnBindingIds().get(0).intValue(),identities.get(0).visibleBindingId());
		Assert.assertEquals(facts.call(1).returnBindingIds().get(0).intValue(),identities.get(1).visibleBindingId());
		Assert.assertEquals(1,identities.get(0).exportChain().size());
		Assert.assertEquals(0,identities.get(0).exportChain().get(0).outputSlot());
		Assert.assertEquals("shared compiled returns have one callee-local marker origin",
			identities.get(0).exportChain().get(0).sourceBindingId(),
			identities.get(1).exportChain().get(0).sourceBindingId());
		long firstNative=MemoProgramModel.nativeDefinitionOrigin(identities.get(0),
			facts.valueBinding(identities.get(0).nativeDefinitionOccurrenceId()));
		long secondNative=MemoProgramModel.nativeDefinitionOrigin(identities.get(1),
			facts.valueBinding(identities.get(1).nativeDefinitionOccurrenceId()));
		Assert.assertEquals("caller targets are exported from one physical definition marker",
			firstNative,secondNative);
		Assert.assertEquals(firstNative,MemoProgramModel.nativeDefinitionOrigin(identities.get(0),
			facts.valueBinding(identities.get(0).nativeDefinitionOccurrenceId()),facts));
		Assert.assertEquals(secondNative,MemoProgramModel.nativeDefinitionOrigin(identities.get(1),
			facts.valueBinding(identities.get(1).nativeDefinitionOccurrenceId()),facts));
		Assert.assertNotEquals(identities.get(0).visibleBindingId(),firstNative);
		Assert.assertNotEquals(identities.get(1).visibleBindingId(),secondNative);

		Set<Integer> firstDependencies = resolver.influencingCompiledOwners(
			facts.call(0).returnBindingIds().get(0));
		Set<Integer> secondDependencies = resolver.influencingCompiledOwners(
			facts.call(1).returnBindingIds().get(0));
		Set<Integer> sharedBody = new LinkedHashSet<>(facts.functionSummary(0).compiledOwnerIds());
		Assert.assertFalse(CollectionsDisjoint.of(firstDependencies, sharedBody));
		Assert.assertFalse(CollectionsDisjoint.of(secondDependencies, sharedBody));
		Set<Integer> firstOnly = new LinkedHashSet<>(firstDependencies);
		firstOnly.removeAll(secondDependencies);
		Set<Integer> secondOnly = new LinkedHashSet<>(secondDependencies);
		secondOnly.removeAll(firstDependencies);
		Assert.assertFalse("the first call must retain its actual dependency", firstOnly.isEmpty());
		Assert.assertFalse("the second call must retain its actual dependency", secondOnly.isEmpty());
	}

	@Test
	public void callCompletionChecksEveryBoundReturnAndOrdersIndependentContinuation() throws Exception {
		DMLProgram program = parsedProgram(
			"f=function(boolean p) return(double Y,double Z){Y=1;if(p){Z=2;}}");
		DataOp before = transientWrite("Before",new LiteralOp(7L));
		FunctionOp call = new FunctionOp(FunctionType.DML,DMLProgram.DEFAULT_NAMESPACE,"f",
			new String[] {"p"},List.of(new LiteralOp(true)),new String[] {"A","B"},true);
		DataOp after = transientWrite("After",new LiteralOp(9L));
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(before,call,after)));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		facts.requireComplete();
		MemoProgramFacts.CallBinding boundCall = facts.call(0);
		Assert.assertFalse("a conditionally missing requested return is not total",
			new MemoBindingResolver(facts).alwaysAvailable(boundCall.completionBindingId(),List.of()));
		Assert.assertEquals(2,boundCall.returnBindingIds().size());
		Assert.assertEquals(-1,facts.occurrence(facts.occurrenceId(before)).activationBindingId());
		Assert.assertEquals("the call and its arguments execute before return validation",-1,
			facts.occurrence(boundCall.callOccurrenceId()).activationBindingId());
		Assert.assertEquals(boundCall.completionBindingId(),
			facts.occurrence(facts.occurrenceId(after)).activationBindingId());

		List<OriginKind> outcomes = new ArrayList<>();
		MemoBindingResolver.VisitResult result = new MemoBindingResolver(facts).visitJoint(
			List.of(boundCall.completionBindingId()),MemoBindingResolver.EvaluationContext.empty(),environment -> {
				outcomes.add(environment.origins().get(0).kind());
				return true;
			});
		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,result.status());
		Assert.assertEquals(Set.of(OriginKind.NORMAL_CONTINUATION,OriginKind.TERMINATED_AT_RETURN),
			new LinkedHashSet<>(outcomes));
		Set<OriginKind> returned = new LinkedHashSet<>();
		new MemoBindingResolver(facts).visitJoint(List.of(boundCall.returnBindingIds().get(0)),
			MemoBindingResolver.EvaluationContext.empty(),environment -> {
				returned.add(environment.origins().get(0).kind()); return true;
			});
		Assert.assertEquals(Set.of(OriginKind.COMPILED_OWNER,OriginKind.TERMINATED_AT_RETURN),returned);
	}

	@Test
	public void unboundUnrequestedReturnDoesNotTerminateTheCall() throws Exception {
		DMLProgram program = parsedProgram(
			"f=function(boolean p) return(double Y,double Z){Y=1;if(p){Z=2;}}");
		FunctionOp call = new FunctionOp(FunctionType.DML,DMLProgram.DEFAULT_NAMESPACE,"f",
			new String[] {"p"},List.of(new LiteralOp(false)),new String[] {"A"},true);
		StatementBlock block = new StatementBlock(); block.setHops(new ArrayList<>(List.of(call)));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		facts.requireComplete();
		MemoProgramFacts.CallBinding boundCall = facts.call(0);
		Assert.assertEquals(1,boundCall.returnBindingIds().size());
		Assert.assertTrue("only requested return slots participate in completion",
			new MemoBindingResolver(facts).alwaysAvailable(boundCall.completionBindingId(),List.of()));
		List<OriginKind> outcomes = new ArrayList<>();
		new MemoBindingResolver(facts).visitJoint(List.of(boundCall.completionBindingId()),
			MemoBindingResolver.EvaluationContext.empty(),environment -> {
				outcomes.add(environment.origins().get(0).kind()); return true;
			});
		// The unused conditional return must not make the completion conditional. The
		// resolver deliberately coalesces the two identical guarded outcomes.
		Assert.assertEquals(List.of(OriginKind.NORMAL_CONTINUATION),outcomes);
	}

	@Test
	public void loopEntryRetainsThePriorCallCompletionPrerequisite() throws Exception {
		DMLProgram program = parsedProgram(
			"f=function(boolean p) return(double Y){Y=1;}i=1;while(i<3){i=i+1;}");
		FunctionOp call = new FunctionOp(FunctionType.DML,DMLProgram.DEFAULT_NAMESPACE,"f",
			new String[] {"p"},List.of(new LiteralOp(true)),new String[] {"R"},true);
		StatementBlock callBlock = new StatementBlock();
		callBlock.setHops(new ArrayList<>(List.of(call)));
		ArrayList<StatementBlock> topLevel = new ArrayList<>();
		topLevel.add(callBlock);
		topLevel.addAll(program.getStatementBlocks());
		program.setStatementBlocks(topLevel);

		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		facts.requireComplete();
		Assert.assertEquals(facts.call(0).completionBindingId(),facts.loop(0).activationBindingId());
		List<OriginKind> outcomes = new ArrayList<>();
		new MemoBindingResolver(facts).visitJoint(List.of(facts.loop(0).activationBindingId()),
			MemoBindingResolver.EvaluationContext.empty(),environment -> {
				outcomes.add(environment.origins().get(0).kind()); return true;
			});
		Assert.assertEquals(List.of(OriginKind.NORMAL_CONTINUATION),outcomes);
	}

	@Test
	public void loopBackedgeRetainsBodyCallCompletionSeparatelyFromEntry() throws Exception {
		DMLProgram program = parsedProgram(
			"f=function(boolean p) return(double Y){Y=1;}i=1;while(i<3){i=i+1;}print(i);");
		WhileStatementBlock loopBlock = program.getStatementBlocks().stream()
			.filter(WhileStatementBlock.class::isInstance).map(WhileStatementBlock.class::cast)
			.findFirst().orElseThrow();
		WhileStatement loopStatement = (WhileStatement) loopBlock.getStatement(0);
		FunctionOp call = new FunctionOp(FunctionType.DML,DMLProgram.DEFAULT_NAMESPACE,"f",
			new String[] {"p"},List.of(new LiteralOp(true)),new String[] {"R"},true);
		StatementBlock callBlock = new StatementBlock();
		callBlock.setHops(new ArrayList<>(List.of(call)));
		loopStatement.getBody().add(0,callBlock);

		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		facts.requireComplete();
		Assert.assertEquals(-1,facts.loop(0).activationBindingId());
		Assert.assertEquals(facts.call(0).completionBindingId(),facts.loop(0).backedgeActivationBindingId());
	}

	@Test
	public void throwingCallOnlyGatesItsExecutedBranchAndJoinContinuation() throws Exception {
		DMLProgram program = parsedProgram("f=function(boolean q) return(double Y){if(q){Y=1;}}"
			+ "p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=1;}else{A=2;}B=A+1;print(B);");
		IfStatementBlock conditional = program.getStatementBlocks().stream()
			.filter(IfStatementBlock.class::isInstance).map(IfStatementBlock.class::cast)
			.findFirst().orElseThrow();
		IfStatement statement = (IfStatement) conditional.getStatement(0);
		FunctionOp call = new FunctionOp(FunctionType.DML,DMLProgram.DEFAULT_NAMESPACE,"f",
			new String[] {"q"},List.of(new LiteralOp(false)),new String[] {"A"},true);
		statement.getIfBody().get(0).setHops(new ArrayList<>(List.of(call)));

		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		facts.requireComplete();
		int consumer = facts.occurrences().stream().filter(occurrence -> occurrence.hop().getInput().stream()
			.anyMatch(input -> isTransientRead(input) && "A".equals(input.getName())))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		int activation = facts.occurrence(consumer).activationBindingId();
		Assert.assertEquals(MemoProgramFacts.BindingKind.SELECT,facts.binding(activation).kind());
		List<MemoBindingResolver.ResolvedEnvironment> environments = new ArrayList<>();
		new MemoBindingResolver(facts).visitJoint(List.of(activation),
			MemoBindingResolver.EvaluationContext.empty(),environment -> {
				environments.add(environment); return true;
			});
		Assert.assertEquals(3,environments.size());
		Assert.assertEquals(1,environments.stream().filter(environment ->
			environment.origins().get(0).kind() == OriginKind.TERMINATED_AT_RETURN).count());
		Assert.assertEquals(2,environments.stream().filter(environment ->
			environment.origins().get(0).kind() == OriginKind.NORMAL_CONTINUATION).count());
	}

	@Test
	public void loopEntryAndBackedgeShareOnePhaseAcrossCarriedSlots() throws Exception {
		MemoProgramFacts facts = analyze("A=matrix(0,2,2);B=matrix(1,2,2);i=1;"
			+ "while(i<3){A=A+1;B=B+2;i=i+1;}print(sum(A)+sum(B));");
		MemoProgramFacts.LoopRecord loop = facts.loop(0);
		List<Integer> roots = loop.carriedValues().stream()
			.filter(value -> "A".equals(value.variable()) || "B".equals(value.variable()))
			.map(MemoProgramFacts.CarriedValue::headBindingId).toList();
		List<MemoBindingResolver.ResolvedEnvironment> environments = new ArrayList<>();
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		resolver.visitJoint(roots, MemoBindingResolver.EvaluationContext.empty(), environment -> {
			environments.add(environment);
			return true;
		});

		Assert.assertEquals("one loop phase applies to every carried slot", 2, environments.size());
		Assert.assertTrue(environments.stream().anyMatch(environment -> environment.origins().stream()
			.allMatch(origin -> origin.boundaryPhase() == BoundaryPhase.ENTRY)));
		Assert.assertTrue(environments.stream().anyMatch(environment -> environment.origins().stream()
			.allMatch(origin -> origin.boundaryPhase() == BoundaryPhase.BACKEDGE)));
		Assert.assertTrue(environments.stream().flatMap(environment -> environment.origins().stream())
			.allMatch(origin -> origin.kind() == OriginKind.COMPILED_OWNER
				|| origin.kind() == OriginKind.LOOP_HEAD_CONTRACT));

		List<MemoBindingResolver.ResolvedOrigin> bounded = new ArrayList<>();
		MemoBindingResolver.LoopDecision backedge = new MemoBindingResolver.LoopDecision(loop.id(), List.of());
		MemoBindingResolver.VisitResult boundedResult = resolver.visitJoint(List.of(roots.get(0)),
			new MemoBindingResolver.EvaluationContext(Map.of(),
				Map.of(backedge, MemoBindingResolver.LoopPhase.BACKEDGE),
				MemoBindingResolver.NO_SELECTION, 1), environment -> {
				bounded.addAll(environment.origins());
				return true;
			});
		Assert.assertEquals(MemoBindingResolver.VisitStatus.UNRESOLVED, boundedResult.status());
		Assert.assertEquals(OriginKind.UNRESOLVED, bounded.get(0).kind());

		MemoProgramFacts.CarriedValue carriedA = loop.carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		List<MemoBindingResolver.ResolvedOrigin> contracted = new ArrayList<>();
		MemoBindingResolver.SelectionLookup contract = new MemoBindingResolver.SelectionLookup() {
			@Override public boolean headIsBound(int loopId, int slot) {
				return loopId == loop.id() && slot == carriedA.slot();
			}

			@Override public MemoPartition selectedHeadLayout(int loopId, int slot) {
				return MemoPartition.ROW;
			}
		};
		resolver.visitJoint(List.of(carriedA.headBindingId()), new MemoBindingResolver.EvaluationContext(
			Map.of(), Map.of(), contract, 64), environment -> {
				contracted.addAll(environment.origins());
				return true;
			});
		Assert.assertEquals(OriginKind.LOOP_HEAD_CONTRACT, contracted.get(0).kind());
		Assert.assertEquals(carriedA.slot(), contracted.get(0).boundarySlot());
		Assert.assertEquals(MemoPartition.ROW, contracted.get(0).selectedLayout());
	}

	@Test
	public void loopAvailabilityInductionIsGroundedAndQueryOrderIndependent() throws Exception {
		MemoProgramFacts valid=analyze("A=matrix(1,2,2);B=matrix(2,2,2);i=1;"
			+"while(i<3){A=A+B;B=B+A;i=i+1;}print(sum(A+B));");
		MemoBindingResolver validResolver=new MemoBindingResolver(valid);
		int validA=valid.loop(0).carriedValues().stream().filter(value -> "A".equals(value.variable()))
			.findFirst().orElseThrow().headBindingId();
		int validB=valid.loop(0).carriedValues().stream().filter(value -> "B".equals(value.variable()))
			.findFirst().orElseThrow().headBindingId();
		Assert.assertTrue(validResolver.alwaysAvailable(validA,List.of()));
		Assert.assertTrue(validResolver.alwaysAvailable(validB,List.of()));

		MemoProgramFacts mixed=analyze("f=function(boolean p) return(double Y){if(p){Y=1;}}"
			+"A=matrix(1,2,2);B=matrix(2,2,2);i=1;while(i<3){A=A+B;B=B+A;i=i+1;}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;R=f(p);print(sum(A+B));");
		int head=mixed.loop(0).carriedValues().stream().filter(value -> "A".equals(value.variable()))
			.findFirst().orElseThrow().headBindingId();
		int conditional=mixed.call(0).completionBindingId();
		MemoBindingResolver firstHead=new MemoBindingResolver(mixed);
		Assert.assertTrue(firstHead.alwaysAvailable(head,List.of()));
		Assert.assertFalse(firstHead.alwaysAvailable(conditional,List.of()));
		MemoBindingResolver firstConditional=new MemoBindingResolver(mixed);
		Assert.assertFalse(firstConditional.alwaysAvailable(conditional,List.of()));
		Assert.assertTrue(firstConditional.alwaysAvailable(head,List.of()));
		var cache=MemoBindingResolver.class.getDeclaredField("totalAvailability"); cache.setAccessible(true);
		Assert.assertEquals("only completed top-level proofs are globally cached",2,
			((Map<?,?>)cache.get(firstHead)).size());
	}

	@Test
	public void unknownWhileExitCardinalityIsOneJointRuntimeDecision() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(0,2,2);B=matrix(1,2,2);i=1;"
			+ "while(i<3){A=A+1;B=B+2;i=i+1;}print(sum(A)+sum(B));");
		MemoProgramFacts.LoopRecord loop=facts.loop(0);
		List<Integer> exits=loop.carriedValues().stream()
			.filter(value -> "A".equals(value.variable()) || "B".equals(value.variable()))
			.map(MemoProgramFacts.CarriedValue::lastBindingId).toList();
		List<MemoBindingResolver.ResolvedEnvironment> environments=new ArrayList<>();
		MemoBindingResolver.VisitResult result=new MemoBindingResolver(facts).visitJoint(exits,
			MemoBindingResolver.EvaluationContext.empty(),environment -> { environments.add(environment); return true; });
		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,result.status());
		Assert.assertEquals("ZERO, ONE and MANY are shared across both carried values",3,environments.size());
		MemoBindingResolver.LoopCardinalityDecision decision=
			new MemoBindingResolver.LoopCardinalityDecision(loop.id(),List.of());
		Assert.assertEquals(Set.of(MemoProgramFacts.LoopCardinality.ZERO,
			MemoProgramFacts.LoopCardinality.ONE,MemoProgramFacts.LoopCardinality.MANY),
			environments.stream().map(environment -> environment.cardinalities().get(decision))
				.collect(java.util.stream.Collectors.toSet()));
		for(MemoBindingResolver.ResolvedEnvironment environment : environments) {
			MemoProgramFacts.LoopCardinality cardinality=environment.cardinalities().get(decision);
			Assert.assertEquals(cardinality==MemoProgramFacts.LoopCardinality.ZERO ? null
				: cardinality==MemoProgramFacts.LoopCardinality.ONE ? MemoProgramFacts.IterationPhase.FIRST
				: MemoProgramFacts.IterationPhase.LAST,
				environment.iterationPhases().get(new MemoBindingResolver.LoopDecision(loop.id(),List.of())));
		}
	}

	@Test
	public void exactZeroAndOneIterationExitsChooseEntryAndLastRespectively() throws Exception {
		for(int count : List.of(0,1)) {
			String range=count==0 ? "seq(1,0,1)" : "1:1";
			MemoProgramFacts facts=analyze("A=matrix(0,2,2);for(i in "+range+"){A=A+1;}print(sum(A));");
			MemoProgramFacts.CarriedValue carried=facts.loop(0).carriedValues().stream()
				.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
			List<MemoBindingResolver.ResolvedEnvironment> environments=new ArrayList<>();
			new MemoBindingResolver(facts).visitJoint(List.of(carried.lastBindingId()),
				MemoBindingResolver.EvaluationContext.empty(),environment -> { environments.add(environment); return true; });
			Assert.assertEquals(1,environments.size());
			MemoProgramFacts.LoopCardinality expected=count==0 ? MemoProgramFacts.LoopCardinality.ZERO
				: MemoProgramFacts.LoopCardinality.ONE;
			Assert.assertEquals(expected,environments.get(0).cardinalities().values().iterator().next());
			MemoBindingResolver resolver=new MemoBindingResolver(facts);
			int selected=count==0 ? carried.entryBindingId() : carried.backedgeBindingId();
			Assert.assertEquals(resolver.resolveOriginIdentity(selected,MemoBindingResolver.EvaluationContext.empty(),
				environments.get(0)).visibleBindingId(),resolver.resolveOriginIdentity(carried.lastBindingId(),
				MemoBindingResolver.EvaluationContext.empty(),environments.get(0)).visibleBindingId());
		}
	}

	@Test
	public void forIteratorIsAnExplicitLocalOrigin() throws Exception {
		MemoProgramFacts facts = analyze("A=matrix(0,2,2);for(i in 1:2){A=A+i;}print(sum(A));");
		MemoProgramFacts.Occurrence iteratorRead = facts.occurrences().stream()
			.filter(occurrence -> isTransientRead(occurrence.hop()) && "i".equals(occurrence.hop().getName()))
			.findFirst().orElseThrow();
		List<MemoBindingResolver.ResolvedOrigin> origins = new ArrayList<>();
		MemoBindingResolver.VisitResult result = new MemoBindingResolver(facts).visitJoint(
			List.of(facts.valueBinding(iteratorRead.id())), MemoBindingResolver.EvaluationContext.empty(),
			environment -> {
				origins.addAll(environment.origins());
				return true;
			});
		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE, result.status());
		Assert.assertEquals(OriginKind.ITERATOR_LOCAL, origins.get(0).kind());
		Assert.assertEquals(MemoPartition.LOCAL, origins.get(0).selectedLayout());
	}

	@Test
	public void identityBackedgeTerminatesAtTheHeadContract() throws Exception {
		MemoProgramFacts facts = analyze("A=matrix(0,2,2);i=1;while(i<3){A=A;i=i+1;}print(sum(A));");
		MemoProgramFacts.CarriedValue carried = facts.loop(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		List<MemoBindingResolver.ResolvedOrigin> backedge = new ArrayList<>();
		List<MemoBindingResolver.OriginIdentity> identities=new ArrayList<>();
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		MemoBindingResolver.LoopDecision decision = new MemoBindingResolver.LoopDecision(0, List.of());
		MemoBindingResolver.EvaluationContext context=new MemoBindingResolver.EvaluationContext(
			Map.of(), Map.of(decision, MemoBindingResolver.LoopPhase.BACKEDGE),
			MemoBindingResolver.NO_SELECTION, 64);
		resolver.visitJoint(List.of(carried.headBindingId()),context, environment -> {
			backedge.addAll(environment.origins());
			identities.add(resolver.resolveOriginIdentity(carried.headBindingId(),context,environment));
			return true;
		});
		Assert.assertEquals(1, backedge.size());
		Assert.assertEquals(OriginKind.LOOP_HEAD_CONTRACT, backedge.get(0).kind());
		Assert.assertEquals(carried.headBindingId(),identities.get(0).visibleBindingId());
	}

	@Test
	public void directSuppliersStopAtTheSelectedLoopHeadContract() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(0,2,2);i=0;while(i<3){B=abs(A);A=abs(A);i=i+1;}"
			+ "print(sum(A)+sum(B));");
		MemoProgramFacts.CarriedValue carried=facts.loop(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		MemoProgramFacts.Occurrence bodyConsumer=facts.occurrences().stream()
			.filter(value -> value.context().contains("/body") && value.hop() instanceof UnaryOp)
			.filter(value -> value.hop().getInput().stream().anyMatch(input -> isTransientRead(input)
				&& "A".equals(input.getName())))
			.findFirst().orElseThrow();
		int input=facts.inputBinding(bodyConsumer.id(),0);
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		Assert.assertTrue("the bound head, not its historical producers, is the immediate supply",
			resolver.directSupplyOwners(input).isEmpty());
		Assert.assertEquals(Set.of(new MemoPrivacy.LoopSlot(facts.loop(0).id(),carried.slot())),
			resolver.directLoopHeads(List.of(input)));
		Assert.assertFalse("boundary validation still sees the concrete backedge producer",
			resolver.directSupplyOwners(carried.backedgeBindingId()).isEmpty());
	}

	@Test
	public void formalActualFindsOnlyItsDirectLoopHeadAcrossTheCallFrame() throws Exception {
		MemoProgramFacts facts = analyze(
			"f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+ "A=matrix(1,2,2);U=matrix(2,2,2);i=1;"
			+ "while(i<3){B=f(A);A=B+1;U=U+1;i=i+1;}print(sum(A)+sum(U));");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		MemoProgramFacts.LoopRecord loop = facts.loop(0);
		MemoProgramFacts.CarriedValue carriedA = loop.carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();
		MemoProgramFacts.CarriedValue carriedU = loop.carriedValues().stream()
			.filter(value -> "U".equals(value.variable())).findFirst().orElseThrow();
		MemoPrivacy.LoopSlot aSlot = new MemoPrivacy.LoopSlot(loop.id(), carriedA.slot());
		MemoPrivacy.LoopSlot uSlot = new MemoPrivacy.LoopSlot(loop.id(), carriedU.slot());
		MemoProgramFacts.CallBinding call = facts.call(0);
		int formal = facts.functionSummary(call.functionSummaryId()).formalBindingIds().get(0);

		Assert.assertEquals(Set.of(aSlot), resolver.directLoopHeads(List.of(formal), List.of(call.id())));
		Assert.assertFalse(resolver.directLoopHeads(List.of(formal), List.of(call.id())).contains(uSlot));
		MemoBindingResolver.EvaluationContext calleeContext=MemoBindingResolver.EvaluationContext.empty()
			.withCallFrame(List.of(call.id()));
		Assert.assertEquals(Set.of(new MemoBindingResolver.DirectLoopHead(loop.id(),carriedA.slot(),List.of())),
			resolver.directLoopHeadIdentities(formal,calleeContext));

		int returned = call.returnBindingIds().get(0);
		Assert.assertTrue("the native function kernel is the direct layout supplier",
			resolver.directLoopHeads(List.of(returned), List.of()).isEmpty());
		Assert.assertTrue("privacy dependencies traverse the native kernel to the actual loop head",
			resolver.relevantLoopHeads(List.of(returned), List.of()).contains(aSlot));
		Assert.assertFalse("an unrelated carried value must not enter the dependency scope",
			resolver.relevantLoopHeads(List.of(returned), List.of()).contains(uSlot));
	}

	@Test
	public void missingDefinitionIsReportedAsUnresolved() {
		DMLProgram program = new DMLProgram();
		DataOp read = transientRead("A");
		DataOp write = transientWrite("B", read);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(write)));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		int readOccurrence = facts.occurrences().stream().filter(o -> o.hop() == read)
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		List<MemoBindingResolver.ResolvedOrigin> origins = new ArrayList<>();
		MemoBindingResolver.VisitResult result = new MemoBindingResolver(facts).visitJoint(
			List.of(facts.valueBinding(readOccurrence)), MemoBindingResolver.EvaluationContext.empty(),
			environment -> {
				origins.addAll(environment.origins());
				return true;
			});
		Assert.assertEquals(MemoBindingResolver.VisitStatus.UNRESOLVED, result.status());
		Assert.assertEquals(OriginKind.MISSING, origins.get(0).kind());
	}

	@Test
	public void iterationPhaseSelectsOnlyTheProvenStructuredArm() throws Exception {
		MemoProgramFacts facts = analyze("X=0;for(i in 1:3){if(i==3){X=3;}else{X=1;}}print(X);");
		int loop=facts.loops().get(0).id();
		int select=facts.bindings().stream().filter(node -> node.kind()==MemoProgramFacts.BindingKind.SELECT
			&& "X".equals(node.label())).mapToInt(MemoProgramFacts.BindingNode::id).findFirst().orElseThrow();
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		Set<Integer> first=new LinkedHashSet<>(),last=new LinkedHashSet<>();
		for(var phase : List.of(MemoProgramFacts.IterationPhase.FIRST,MemoProgramFacts.IterationPhase.LAST)) {
			var context=MemoBindingResolver.EvaluationContext.empty().withIterationPhase(loop,phase);
			Set<Integer> target=phase==MemoProgramFacts.IterationPhase.FIRST ? first : last;
			MemoBindingResolver.VisitResult result=resolver.visitJoint(List.of(select),context,environment -> {
				target.add(environment.origins().get(0).compiledOwnerId()); return true;
			});
			Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,result.status());
			Assert.assertEquals("a proven phase must not enumerate the opposite branch",1,target.size());
		}
		Assert.assertNotEquals(first,last);
	}

	private static MemoProgramFacts analyze(String script) throws Exception {
		return MemoProgramFacts.analyze(parsedProgram(script));
	}

	private static DMLProgram parsedProgram(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static int binaryTransientConsumer(MemoProgramFacts facts, String left, String right) {
		return facts.occurrences().stream().filter(o -> o.hop().getInput().size() == 2
			&& isTransientRead(o.hop().getInput(0)) && left.equals(o.hop().getInput(0).getName())
			&& isTransientRead(o.hop().getInput(1)) && right.equals(o.hop().getInput(1).getName()))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
	}

	private static boolean isTransientRead(Hop hop) {
		return hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTREAD;
	}

	private static DataOp transientRead(String name) {
		return new DataOp(name, DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, name, 0, 0, -1, -1);
	}

	private static DataOp transientWrite(String name, Hop input) {
		return new DataOp(name, input.getDataType(), input.getValueType(), input,
			OpOpData.TRANSIENTWRITE, name);
	}

	private static final class CollectionsDisjoint {
		private static boolean of(Set<Integer> left, Set<Integer> right) {
			for(int value : left)
				if(right.contains(value)) return false;
			return true;
		}
	}
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.InputRole;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.meta.MetaDataAll;
import org.junit.Assert;
import org.junit.Test;

public class MemoPrivacyTest {
	@Test
	public void federatedResidencyNeedsNoPrivacyValuationButLocalResidencyStillDoes() throws Exception {
		MemoProgramFacts facts=analyze("X=matrix(1,2,2);A=abs(X);print(sum(A));");
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),
			Map.of(onlyOwner(facts,"X"),Privacy.PRIVATE));
		int occurrence=facts.occurrences().stream().filter(o -> o.hop() instanceof UnaryOp
			&& ((UnaryOp)o.hop()).getOp()==OpOp1.ABS).mapToInt(MemoProgramFacts.Occurrence::id)
			.findFirst().orElseThrow();
		var fed=new RuleMemo.Rule(ExecType.FED,List.of(MemoPartition.ROW),List.of(MemoPartition.ROW),"test");
		long before=privacy.statistics().evaluations();
		Assert.assertEquals(MemoPrivacy.Legality.LEGAL,privacy.unconditionalRuleLegalityAt(occurrence,fed,
			List.of(InputRole.PAYLOAD),MemoPrivacy.Context.empty()).legality());
		Assert.assertEquals("all privacy valuations permit federated residency",before,privacy.statistics().evaluations());
		var local=new RuleMemo.Rule(ExecType.CP,List.of(MemoPartition.LOCAL),List.of(MemoPartition.LOCAL),"test");
		Assert.assertEquals(MemoPrivacy.Legality.ILLEGAL,privacy.unconditionalRuleLegalityAt(occurrence,local,
			List.of(InputRole.PAYLOAD),MemoPrivacy.Context.empty()).legality());
		Assert.assertTrue(privacy.statistics().evaluations()>before);
	}

	@Test
	public void strictPrivateUnaryAndAggregateRemainPrivate() throws Exception {
		MemoProgramFacts facts = analyze("X=matrix(1,2,2);A=abs(X);s=sum(A);print(s);");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		int source = onlyOwner(facts, "X");
		MemoPrivacy privacy = new MemoPrivacy(facts, resolver, Map.of(source, Privacy.PRIVATE));

		Assert.assertEquals(Privacy.PRIVATE,
			privacy.outputPrivacy(onlyOwner(facts, "A"), MemoPrivacy.Context.empty()).privacy());
		Assert.assertEquals(Privacy.PRIVATE,
			privacy.outputPrivacy(onlyOwner(facts, "s"), MemoPrivacy.Context.empty()).privacy());
	}

	@Test
	public void privateAggregateFullSumIsReleasedByExistingTransferSemantics() throws Exception {
		MemoProgramFacts facts = analyze("X=matrix(1,2,2);s=sum(X);print(s);");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		MemoPrivacy privacy = new MemoPrivacy(facts, resolver,
			Map.of(onlyOwner(facts, "X"), Privacy.PRIVATE_AGGREGATE));

		MemoPrivacy.Result result = privacy.outputPrivacy(
			onlyOwner(facts, "s"), MemoPrivacy.Context.empty());
		Assert.assertEquals(MemoPrivacy.Status.EXACT, result.status());
		Assert.assertEquals(Privacy.PUBLIC, result.privacy());
	}

	@Test
	public void branchPrivacyIsExactOnlyUnderItsGuardEnvironment() throws Exception {
		MemoProgramFacts facts = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);}else{A=matrix(2,2,2);}C=A+1;print(sum(C));");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		Map<Integer,Privacy> sources = new LinkedHashMap<>();
		facts.occurrences().stream().filter(occurrence -> "A".equals(occurrence.hop().getName()))
			.filter(occurrence -> occurrence.context().contains("/then") || occurrence.context().contains("/else"))
			.forEach(occurrence -> sources.put(occurrence.compiledOwnerId(),
				occurrence.context().contains("/then") ? Privacy.PUBLIC : Privacy.PRIVATE));
		MemoPrivacy privacy = new MemoPrivacy(facts, resolver, sources);
		int consumer = facts.occurrences().stream().filter(occurrence -> "C".equals(occurrence.hop().getName())
			&& occurrence.hop().getInput().stream().anyMatch(input -> "A".equals(input.getName())))
			.mapToInt(MemoProgramFacts.Occurrence::id)
			.findFirst().orElseThrow();
		int binding = facts.inputBinding(consumer, 0);
		int guard = facts.guards().get(0).id();

		MemoPrivacy.Result unspecified = privacy.privacyAt(binding, MemoPrivacy.Context.empty());
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN, unspecified.status());
		Assert.assertEquals(Privacy.PUBLIC, privacy.privacyAt(binding, contextWithGuard(guard, true)).privacy());
		Assert.assertEquals(Privacy.PRIVATE, privacy.privacyAt(binding, contextWithGuard(guard, false)).privacy());
	}

	@Test
	public void conditionalProjectionVisitsEveryRelevantPrivacyArmWithoutDefaultingPublic() throws Exception {
		MemoProgramFacts facts = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);}else{A=matrix(2,2,2);}C=A+1;print(sum(C));");
		Map<Integer,Privacy> sources = new LinkedHashMap<>();
		facts.occurrences().stream().filter(occurrence -> "A".equals(occurrence.hop().getName()))
			.filter(occurrence -> occurrence.context().contains("/then") || occurrence.context().contains("/else"))
			.forEach(occurrence -> sources.put(occurrence.compiledOwnerId(),
				occurrence.context().contains("/then") ? Privacy.PUBLIC : Privacy.PRIVATE));
		MemoPrivacy privacy = new MemoPrivacy(facts, new MemoBindingResolver(facts), sources);
		int consumer = facts.occurrences().stream().filter(occurrence -> "C".equals(occurrence.hop().getName())
			&& occurrence.hop().getInput().stream().anyMatch(input -> "A".equals(input.getName())))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		int binding = facts.inputBinding(consumer, 0);
		Set<Privacy> visited = new java.util.LinkedHashSet<>();
		MemoBindingResolver.VisitResult result = privacy.visitConditionalInputs(List.of(binding),
			MemoPrivacy.Context.empty(), context -> {
				MemoPrivacy.Result exact = privacy.privacyAt(binding, context);
				Assert.assertEquals(MemoPrivacy.Status.EXACT, exact.status());
				visited.add(exact.privacy());
				return true;
			});

		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE, result.status());
		Assert.assertEquals(Set.of(Privacy.PUBLIC, Privacy.PRIVATE), visited);
	}

	@Test
	public void callsRetainActualSpecificPrivacy() throws Exception {
		MemoProgramFacts facts = analyze("f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+ "P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=f(Q);C=A+B;print(sum(C));");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		int firstActual = resolver.directSupplyOwners(facts.call(0).actualBindingIds().get(0)).iterator().next();
		int secondActual = resolver.directSupplyOwners(facts.call(1).actualBindingIds().get(0)).iterator().next();
		MemoPrivacy privacy = new MemoPrivacy(facts, resolver, Map.of(
			firstActual, Privacy.PRIVATE, secondActual, Privacy.PUBLIC));
		int consumer = facts.occurrences().stream().filter(occurrence -> occurrence.hop().getInput().size() == 2
			&& "A".equals(occurrence.hop().getInput(0).getName())
			&& "B".equals(occurrence.hop().getInput(1).getName()))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();

		Assert.assertEquals(Privacy.PRIVATE,
			privacy.privacyAt(facts.inputBinding(consumer, 0), MemoPrivacy.Context.empty()).privacy());
		Assert.assertEquals(Privacy.PUBLIC,
			privacy.privacyAt(facts.inputBinding(consumer, 1), MemoPrivacy.Context.empty()).privacy());
	}

	@Test
	public void irrelevantBranchDecisionsReusePrivacyWithoutHidingARelevantChange() throws Exception {
		MemoProgramFacts facts=analyze("p=as.scalar(rand(rows=1,cols=1));q=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);}else{A=matrix(2,2,2);}"
			+ "if(q>0.5){z=1;}else{z=2;}C=A+1;print(sum(C)+z);");
		Map<Integer,Privacy> sources=new LinkedHashMap<>();
		facts.occurrences().stream().filter(o -> "A".equals(o.hop().getName()))
			.filter(o -> o.context().contains("/then") || o.context().contains("/else"))
			.forEach(o -> sources.put(o.compiledOwnerId(),o.context().contains("/then") ? Privacy.PUBLIC : Privacy.PRIVATE));
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),sources);
		int consumer=facts.occurrences().stream().filter(o -> "C".equals(o.hop().getName())
			&& o.hop().getInput().stream().anyMatch(h -> "A".equals(h.getName())))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		int binding=facts.inputBinding(consumer,0),p=facts.guards().get(0).id(),q=facts.guards().get(1).id();
		for(boolean unrelated : new boolean[] {false,true}) {
			var context=new MemoPrivacy.Context(new EvaluationContext(Map.of(
				new GuardDecision(p,List.of()),true,new GuardDecision(q,List.of()),unrelated),
				Map.of(),MemoBindingResolver.NO_SELECTION,4096),Map.of());
			long before=privacy.statistics().evaluations();
			Assert.assertEquals(Privacy.PUBLIC,privacy.privacyAt(binding,context).privacy());
			if(unrelated) Assert.assertEquals("unrelated guard must not repeat value analysis",before,
				privacy.statistics().evaluations());
		}
		Assert.assertEquals(Privacy.PRIVATE,privacy.privacyAt(binding,contextWithGuard(p,false)).privacy());
	}

	@Test
	public void zeroAndNonzeroExitPrivacyStayCorrelatedInTheCache() throws Exception {
		MemoProgramFacts facts=analyze("X=matrix(1,2,2);A=matrix(2,2,2);"
			+ "limit=as.scalar(rand(rows=1,cols=1));i=0;while(i<limit){A=abs(X);i=i+1;}"
			+ "C=A+1;print(sum(C));");
		MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),
			Map.of(facts.occurrences().stream().filter(o -> o.hop() instanceof org.apache.sysds.hops.DataGenOp
				&& "X".equals(o.hop().getName())).mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId)
				.findFirst().orElseThrow(),Privacy.PRIVATE));
		int consumer=facts.occurrences().stream().filter(o -> "C".equals(o.hop().getName())
			&& o.hop().getInput().stream().anyMatch(h -> "A".equals(h.getName())))
			.mapToInt(MemoProgramFacts.Occurrence::id).findFirst().orElseThrow();
		int binding=facts.inputBinding(consumer,0),loop=facts.loops().get(0).id();
		for(var cardinality : List.of(MemoProgramFacts.LoopCardinality.ZERO,
			MemoProgramFacts.LoopCardinality.ONE,MemoProgramFacts.LoopCardinality.MANY,
			MemoProgramFacts.LoopCardinality.ZERO)) {
			var context=new MemoPrivacy.Context(EvaluationContext.empty().withLoopCardinality(loop,cardinality),Map.of());
			Assert.assertEquals(cardinality==MemoProgramFacts.LoopCardinality.ZERO ? Privacy.PUBLIC : Privacy.PRIVATE,
				privacy.privacyAt(binding,context).privacy());
		}
	}

	@Test
	public void requestedReturnPrivacyRetainsWholeCallCompletionDependencies() throws Exception {
		DMLProgram program=parsedProgram("f=function(boolean p) return(double Y,double Z){Y=1;if(p){Z=2;}}");
		var call=new org.apache.sysds.hops.FunctionOp(org.apache.sysds.hops.FunctionOp.FunctionType.DML,
			DMLProgram.DEFAULT_NAMESPACE,"f",new String[] {"p"},List.of(new org.apache.sysds.hops.LiteralOp(true)),
			new String[] {"A","B"},true);
		StatementBlock block=new StatementBlock(); block.setHops(new ArrayList<>(List.of(call)));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		int binding=facts.call(0).returnBindingIds().get(0);
		int missing=facts.call(0).returnBindingIds().get(1),guard=facts.guards().get(0).id();
		for(boolean first : new boolean[] {false,true}) {
			MemoPrivacy privacy=new MemoPrivacy(facts,new MemoBindingResolver(facts),Map.of());
			for(boolean arm : new boolean[] {first,!first,first}) {
				var context=new MemoPrivacy.Context(new EvaluationContext(
					Map.of(new GuardDecision(guard,List.of(0)),arm),Map.of(),MemoBindingResolver.NO_SELECTION,4096),Map.of());
				Assert.assertEquals(arm ? MemoPrivacy.Status.EXACT : MemoPrivacy.Status.UNKNOWN,
					privacy.privacyAt(binding,context).status());
				if(!arm) {
					Assert.assertEquals("payload-only mode may ignore whole-call completion after edge availability",
						Privacy.PUBLIC,privacy.privacyAt(binding,context.withPayloadOnly()).privacy());
					Assert.assertEquals("an actually missing requested payload remains unknown",
						MemoPrivacy.Status.UNKNOWN,privacy.privacyAt(missing,context.withPayloadOnly()).status());
					Assert.assertEquals("payload-only cache entries must not bypass direct availability semantics",
						MemoPrivacy.Status.UNKNOWN,privacy.privacyAt(binding,context).status());
				}
			}
		}
	}

	@Test
	public void loopSummaryIsGroundedAtEntryAndRequiresAnInvariantPrivacy() throws Exception {
		MemoProgramFacts facts = analyze("A=matrix(1,2,2);i=1;"
			+ "while(i<3){A=abs(A);i=i+1;}print(sum(A));");
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		MemoPrivacy privacy = new MemoPrivacy(facts, resolver,
			Map.of(ownerOutsideLoop(facts, "A"), Privacy.PRIVATE));
		MemoProgramFacts.CarriedValue carried = facts.loop(0).carriedValues().stream()
			.filter(value -> "A".equals(value.variable())).findFirst().orElseThrow();

		MemoPrivacy.Result result = privacy.summarizeLoop(0, MemoPrivacy.Context.empty())
			.get(new MemoPrivacy.LoopSlot(0, carried.slot()));
		Assert.assertEquals(MemoPrivacy.Status.EXACT, result.status());
		Assert.assertEquals(Privacy.PRIVATE, result.privacy());
	}

	@Test
	public void protectedMetadataCanBeReadRemotelyButNotCollected() throws Exception {
		DataOp read = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "input", 2, 2, 4, 1000);
		UnaryOp nrow = new UnaryOp("n", DataType.SCALAR, ValueType.INT64, OpOp1.NROW, read);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(nrow)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		int metadataOwner = facts.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == nrow)
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
		int source = facts.occurrences().stream().filter(occurrence -> occurrence.hop() == read)
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
		MemoPrivacy privacy = new MemoPrivacy(facts, resolver, Map.of(source, Privacy.PRIVATE_AGGREGATE));

		RuleMemo.Rule remote = new RuleMemo.Rule(ExecType.CP, List.of(MemoPartition.ROW),
			List.of(MemoPartition.LOCAL), "metadata/remote");
		RuleMemo.Rule collected = new RuleMemo.Rule(ExecType.CP, List.of(MemoPartition.LOCAL),
			List.of(MemoPartition.LOCAL), "metadata/collected");
		Assert.assertEquals(MemoPrivacy.Legality.LEGAL, privacy.unconditionalRuleLegality(
			metadataOwner, remote, List.of(InputRole.METADATA), MemoPrivacy.Context.empty()).legality());
		Assert.assertEquals(MemoPrivacy.Legality.ILLEGAL, privacy.unconditionalRuleLegality(
			metadataOwner, collected, List.of(InputRole.METADATA), MemoPrivacy.Context.empty()).legality());
	}

	@Test
	public void absentExternalSourceAuthorityIsUnknownRatherThanPublic() throws Exception {
		DataOp read = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "missing", 2, 2, 4, 1000);
		UnaryOp abs = new UnaryOp("A", DataType.MATRIX, ValueType.FP64, OpOp1.ABS, read);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(abs)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoBindingResolver resolver = new MemoBindingResolver(facts);
		MemoPrivacy.Result result = new MemoPrivacy(facts, resolver, Map.of())
			.outputPrivacy(facts.occurrences().stream().filter(occurrence -> occurrence.hop() == abs)
				.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow(),
				MemoPrivacy.Context.empty());
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN, result.status());
	}

	@Test
	public void sharedOwnerPrivacyIsResolvedPerOccurrence() {
		DataOp privateSource = new DataOp("P", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "P", 2, 2, 4, 1000);
		DataOp publicSource = new DataOp("Q", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, "Q", 2, 2, 4, 1000);
		DataOp sharedRead = new DataOp("A", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "A", 2, 2, 4, 1000);
		StatementBlock first = new StatementBlock();
		first.setHops(new ArrayList<>(List.of(
			new DataOp("A", DataType.MATRIX, ValueType.FP64, privateSource, OpOpData.TRANSIENTWRITE, "A"),
			new DataOp("U", DataType.MATRIX, ValueType.FP64, sharedRead, OpOpData.TRANSIENTWRITE, "U"))));
		StatementBlock second = new StatementBlock();
		second.setHops(new ArrayList<>(List.of(
			new DataOp("A", DataType.MATRIX, ValueType.FP64, publicSource, OpOpData.TRANSIENTWRITE, "A"),
			new DataOp("V", DataType.MATRIX, ValueType.FP64, sharedRead, OpOpData.TRANSIENTWRITE, "V"))));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(first, second)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoPrivacy privacy = new MemoPrivacy(facts, new MemoBindingResolver(facts), Map.of(
			facts.compiledOwnerId(privateSource), Privacy.PRIVATE,
			facts.compiledOwnerId(publicSource), Privacy.PUBLIC));
		List<Integer> occurrences = facts.occurrenceIds(sharedRead);
		Assert.assertEquals(2, occurrences.size());
		Assert.assertEquals(Privacy.PRIVATE, privacy.outputPrivacyAt(
			occurrences.get(0), MemoPrivacy.Context.empty()).privacy());
		Assert.assertEquals(Privacy.PUBLIC, privacy.outputPrivacyAt(
			occurrences.get(1), MemoPrivacy.Context.empty()).privacy());
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN, privacy.outputPrivacy(
			facts.compiledOwnerId(sharedRead), MemoPrivacy.Context.empty()).status());

		RuleMemo.Rule local = new RuleMemo.Rule(ExecType.CP, List.of(),
			List.of(MemoPartition.LOCAL), "shared/tread");
		Assert.assertEquals(MemoPrivacy.Legality.ILLEGAL, privacy.unconditionalRuleLegalityAt(
			occurrences.get(0), local, List.of(), MemoPrivacy.Context.empty()).legality());
		Assert.assertEquals(MemoPrivacy.Legality.LEGAL, privacy.unconditionalRuleLegalityAt(
			occurrences.get(1), local, List.of(), MemoPrivacy.Context.empty()).legality());
	}

	@Test
	public void persistentReadPrivacyUsesDeclaredAuthorityAndNeverFilenamePrivacy() {
		HashMap<String,Hop> protectedParameters = new HashMap<>();
		protectedParameters.put(DataExpression.IO_FILENAME, new org.apache.sysds.hops.LiteralOp("protected.csv"));
		protectedParameters.put(DataExpression.PRIVACY, new org.apache.sysds.hops.LiteralOp("private"));
		DataOp protectedRead = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, protectedParameters);
		HashMap<String,Hop> unknownParameters = new HashMap<>();
		unknownParameters.put(DataExpression.IO_FILENAME, new org.apache.sysds.hops.LiteralOp("unknown.csv"));
		DataOp unknownRead = new DataOp("Y", DataType.MATRIX, ValueType.FP64,
			OpOpData.PERSISTENTREAD, unknownParameters);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(protectedRead, unknownRead)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		MemoPrivacy privacy = new MemoPrivacy(facts, new MemoBindingResolver(facts), Map.of());

		Assert.assertEquals(Privacy.PRIVATE, privacy.outputPrivacyAt(
			facts.occurrenceId(protectedRead), MemoPrivacy.Context.empty()).privacy());
		Assert.assertEquals(MemoPrivacy.Status.UNKNOWN, privacy.outputPrivacyAt(
			facts.occurrenceId(unknownRead), MemoPrivacy.Context.empty()).status());
	}

	@Test
	public void localMetadataPrivacyIsAcquiredOnceWithoutDefaultingMissingAuthority() throws Exception {
		Path privateData = Files.createTempFile("memo-private-source", ".bin");
		Path publicData = Files.createTempFile("memo-public-source", ".bin");
		Path missingData = Files.createTempFile("memo-missing-source", ".bin");
		Path privateMetadata = Path.of(privateData + ".mtd");
		Path publicMetadata = Path.of(publicData + ".mtd");
		try {
			Files.writeString(privateMetadata, metadataJson(",\"privacy\":\"private\""));
			Files.writeString(publicMetadata, metadataJson(""));
			DataOp privateRead = new DataOp("P", DataType.MATRIX, ValueType.FP64,
				OpOpData.PERSISTENTREAD, privateData.toString(), 2, 2, 4, 1000);
			DataOp publicRead = new DataOp("Q", DataType.MATRIX, ValueType.FP64,
				OpOpData.PERSISTENTREAD, publicData.toString(), 2, 2, 4, 1000);
			DataOp missingRead = new DataOp("R", DataType.MATRIX, ValueType.FP64,
				OpOpData.PERSISTENTREAD, missingData.toString(), 2, 2, 4, 1000);
			StatementBlock block = new StatementBlock();
			block.setHops(new ArrayList<>(List.of(privateRead, publicRead, missingRead)));
			DMLProgram program = new DMLProgram();
			program.setStatementBlocks(new ArrayList<>(List.of(block)));
			MemoProgramFacts facts = MemoProgramFacts.analyze(program);
			MemoSources sources = MemoSources.acquire(facts, null);

			Assert.assertEquals(Privacy.PRIVATE, sources.privacy(facts.compiledOwnerId(privateRead)));
			Assert.assertEquals(Privacy.PUBLIC, sources.privacy(facts.compiledOwnerId(publicRead)));
			Assert.assertNull(sources.privacy(facts.compiledOwnerId(missingRead)));
			MemoPrivacy privacy = new MemoPrivacy(facts, new MemoBindingResolver(facts), sources);
			Assert.assertEquals(MemoPrivacy.Status.UNKNOWN, privacy.outputPrivacyAt(
				facts.occurrenceId(missingRead), MemoPrivacy.Context.empty()).status());
		}
		finally {
			Files.deleteIfExists(privateMetadata);
			Files.deleteIfExists(publicMetadata);
			Files.deleteIfExists(privateData);
			Files.deleteIfExists(publicData);
			Files.deleteIfExists(missingData);
		}
	}

	@Test
	public void explicitCoarsePrivacyCannotHideFineGrainedSidecar() throws Exception {
		Path data = Files.createTempFile("memo-explicit-coarse-fine-source", ".bin");
		Path metadata = Path.of(data + ".mtd");
		try {
			Files.writeString(metadata, metadataJson(",\"privacy\":\"public\","
				+ "\"fine_grained_privacy\":{\"Private\":[[[0,0],[0,1]]] }"));
			HashMap<String,Hop> parameters = new HashMap<>();
			parameters.put(DataExpression.IO_FILENAME, new org.apache.sysds.hops.LiteralOp(data.toString()));
			parameters.put(DataExpression.PRIVACY, new org.apache.sysds.hops.LiteralOp("public"));
			DataOp read = new DataOp("X", DataType.MATRIX, ValueType.FP64,
				OpOpData.PERSISTENTREAD, parameters);
			StatementBlock block = new StatementBlock();
			block.setHops(new ArrayList<>(List.of(read)));
			DMLProgram program = new DMLProgram();
			program.setStatementBlocks(new ArrayList<>(List.of(block)));

			try {
				MemoSources.acquire(MemoProgramFacts.analyze(program), null);
				Assert.fail("Explicit coarse privacy must not bypass fine-grained sidecar validation");
			}
			catch(MetaDataAll.UnsupportedFineGrainedPrivacyException expected) {
				Assert.assertEquals(MetaDataAll.UNSUPPORTED_FINE_GRAINED_PRIVACY, expected.code());
			}
		}
		finally {
			Files.deleteIfExists(metadata);
			Files.deleteIfExists(data);
		}
	}

	private static String metadataJson(String privacy) {
		return "{\"data_type\":\"matrix\",\"value_type\":\"double\",\"rows\":2,"
			+ "\"cols\":2,\"nnz\":4,\"format\":\"binary\"" + privacy + '}';
	}

	private static MemoPrivacy.Context contextWithGuard(int guard, boolean value) {
		return new MemoPrivacy.Context(new EvaluationContext(
			Map.of(new GuardDecision(guard, List.of()), value), Map.of(),
			MemoBindingResolver.NO_SELECTION, 4096), Map.of());
	}

	private static int onlyOwner(MemoProgramFacts facts, String variable) {
		Set<Integer> owners = facts.occurrences().stream().filter(occurrence -> variable.equals(occurrence.hop().getName()))
			.map(MemoProgramFacts.Occurrence::compiledOwnerId)
			.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
		if(owners.size() != 1)
			throw new AssertionError("Expected one owner for " + variable + " but got " + owners);
		return owners.iterator().next();
	}

	private static int ownerOutsideLoop(MemoProgramFacts facts, String variable) {
		return facts.occurrences().stream().filter(occurrence -> variable.equals(occurrence.hop().getName()))
			.filter(occurrence -> !occurrence.context().contains("/loop-"))
			.mapToInt(MemoProgramFacts.Occurrence::compiledOwnerId).findFirst().orElseThrow();
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
}

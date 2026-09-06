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
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Grounded worker-pool proofs for loop-carried exact candidate rows. */
public class WorkerPoolAnchorResolverCycleTest {
	@Test
	public void stepLmNestedLoopBodyWriteRetainsSeedPoolForAllActualFullAppendRows() throws Exception {
		Fixture f = new Fixture();
		Ref seed = f.source("seed", "localhost:1234");
		Ref appendedColumn = f.source("z-appended-column", "localhost:1234");
		f.privacy(appendedColumn, Privacy.PRIVATE_AGGREGATE);
		Ref headerRead = f.read("header-read");
		Ref append = f.append("loop-append", headerRead, appendedColumn);
		f.candidate(append, FType.FULL,
			CandidateInputState.present(FType.FULL), CandidateInputState.absentLocal());
		f.candidate(append, FType.FULL,
			CandidateInputState.absentLocal(), CandidateInputState.present(FType.FULL));
		f.candidate(append, FType.FULL,
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.BROADCAST));
		f.candidate(append, FType.FULL,
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		Ref loopWrite = f.write("loop-write", append);
		f.candidate(loopWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		f.definitions(headerRead, seed, loopWrite);
		Ref identityWrite = f.write("loop-body-identity-write", headerRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Set<DurableAnchorKey> resolved = f.resolve(consumerRead163, FType.FULL);
		Assert.assertEquals("The one-predecessor wrapper must resolve one concrete pool", 1, resolved.size());
		Assert.assertTrue("The resolved representative must be the grounded seed/A worker pool",
			PlacementIdentity.samePhysicalWorkerPool(seed.anchor, resolved.iterator().next()));
	}

	@Test
	public void stepLmNestedPureCycleDoesNotInventPool() throws Exception {
		Fixture f = new Fixture();
		Ref appendedColumn = f.read("local-column");
		Ref headerRead = f.read("header-read");
		Ref updateWrite = f.update("update", headerRead, appendedColumn,
			CandidateInputState.present(FType.FULL), CandidateInputState.absentLocal());
		f.definitions(headerRead, updateWrite);
		Ref identityWrite = f.write("loop-body-identity-write", headerRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Assert.assertTrue(f.resolve(consumerRead163, FType.FULL).isEmpty());
	}

	@Test
	public void stepLmNestedAlternativeFullRowOnDifferentPoolRemainsAmbiguous() throws Exception {
		Fixture f = new Fixture();
		Ref firstPool = f.source("first-pool", "localhost:1234");
		Ref secondPool = f.source("second-pool", "localhost:1235");
		Ref headerRead = f.read("header-read");
		Ref samePoolUpdate = f.update("same-pool-update", headerRead, firstPool,
			CandidateInputState.present(FType.FULL), CandidateInputState.absentLocal());
		Ref otherPoolUpdate = f.update("other-pool-update", headerRead, secondPool,
			CandidateInputState.absentLocal(), CandidateInputState.present(FType.FULL));
		f.definitions(headerRead, firstPool, samePoolUpdate, otherPoolUpdate);
		Ref identityWrite = f.write("loop-body-identity-write", headerRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Assert.assertTrue("Every selectable FULL/FOUT row must retain the same concrete pool",
			f.resolve(consumerRead163, FType.FULL).isEmpty());
	}

	@Test
	public void nestedUnknownCfgDefinitionInvalidatesOtherwiseGroundedPool() throws Exception {
		Fixture f = new Fixture();
		Ref seed = f.source("seed", "localhost:1234");
		Ref nestedRead = f.read("nested-read");
		f.definitions(nestedRead, seed);
		f.addPredecessor(nestedRead, "cfg-definition:unknown-reference");
		Ref identityWrite = f.write("loop-body-identity-write", nestedRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Assert.assertTrue(f.resolve(consumerRead163, FType.FULL).isEmpty());
	}

	@Test
	public void nestedMixedCfgAndDifferentFunctionArgumentInvalidatesPool() throws Exception {
		Fixture f = new Fixture();
		Ref seed = f.source("seed", "localhost:1234");
		Ref differentArgument = f.source("function-argument", "localhost:1235");
		Ref nestedRead = f.read("nested-read");
		f.definitions(nestedRead, seed);
		f.functionInput(nestedRead, differentArgument);
		Ref identityWrite = f.write("loop-body-identity-write", nestedRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Assert.assertTrue(f.resolve(consumerRead163, FType.FULL).isEmpty());
	}

	@Test
	public void nestedMixedCfgAndSamePoolFunctionArgumentRemainsGrounded() throws Exception {
		Fixture f = new Fixture();
		Ref seed = f.source("seed", "localhost:1234");
		Ref samePoolArgument = f.source("function-argument", "localhost:1234");
		Ref nestedRead = f.read("nested-read");
		f.definitions(nestedRead, seed);
		f.functionInput(nestedRead, samePoolArgument);
		Ref identityWrite = f.write("loop-body-identity-write", nestedRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Set<DurableAnchorKey> resolved = f.resolve(consumerRead163, FType.FULL);
		Assert.assertEquals(1, resolved.size());
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerPool(
			seed.anchor, resolved.iterator().next()));
	}

	@Test
	public void nestedDeclaredFunctionInputWithoutCallerArgumentIsIncomplete() throws Exception {
		Fixture f = new Fixture();
		Ref seed = f.source("seed", "localhost:1234");
		Ref nestedRead = f.read("nested-read");
		f.definitions(nestedRead, seed);
		f.missingFunctionInput(nestedRead);
		Ref identityWrite = f.write("loop-body-identity-write", nestedRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Assert.assertTrue(f.resolve(consumerRead163, FType.FULL).isEmpty());
	}

	@Test
	public void nestedDerivedFunctionReturnRetainsSeedPoolThroughSyntheticOutputBoundary() throws Exception {
		Fixture f = new Fixture();
		Ref seed = f.source("seed", "localhost:1234");
		Ref headerRead = f.read("header-read");
		Ref returned = f.unary("returned", headerRead);
		Ref outputBoundary = f.functionOutput("output-boundary");
		Ref nestedRead = f.read("nested-read");
		f.functionOutput(nestedRead, outputBoundary, returned);
		Ref loopWrite = f.write("loop-write", nestedRead);
		f.candidate(loopWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		f.definitions(headerRead, seed, loopWrite);
		Ref identityWrite = f.write("loop-body-identity-write", headerRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Set<DurableAnchorKey> resolved = f.resolve(consumerRead163, FType.FULL);
		Assert.assertEquals(1, resolved.size());
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerPool(
			seed.anchor, resolved.iterator().next()));
	}

	@Test
	public void nestedDerivedFunctionReturnOnDifferentPoolInvalidatesSeedPool() throws Exception {
		Fixture f = new Fixture();
		Ref seed = f.source("seed", "localhost:1234");
		Ref other = f.source("other", "localhost:1235");
		Ref headerRead = f.read("header-read");
		Ref returned = f.unary("returned", other);
		Ref outputBoundary = f.functionOutput("output-boundary");
		Ref nestedRead = f.read("nested-read");
		f.functionOutput(nestedRead, outputBoundary, returned);
		Ref loopWrite = f.write("loop-write", nestedRead);
		f.candidate(loopWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		f.definitions(headerRead, seed, loopWrite);
		Ref identityWrite = f.write("loop-body-identity-write", headerRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Assert.assertTrue(f.resolve(consumerRead163, FType.FULL).isEmpty());
	}

	@Test
	public void nestedUnknownFunctionReturnSourceInvalidatesSeedPool() throws Exception {
		Fixture f = new Fixture();
		Ref seed = f.source("seed", "localhost:1234");
		Ref headerRead = f.read("header-read");
		Ref returned = f.read("unknown-returned-value");
		Ref outputBoundary = f.functionOutput("output-boundary");
		Ref nestedRead = f.read("nested-read");
		f.functionOutput(nestedRead, outputBoundary, returned);
		Ref loopWrite = f.write("loop-write", nestedRead);
		f.candidate(loopWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		f.definitions(headerRead, seed, loopWrite);
		Ref identityWrite = f.write("loop-body-identity-write", headerRead);
		f.candidate(identityWrite, FType.FULL, CandidateInputState.present(FType.FULL));
		Ref consumerRead163 = f.read("consumer-read-163");
		f.definitions(consumerRead163, identityWrite);

		Assert.assertTrue(f.resolve(consumerRead163, FType.FULL).isEmpty());
	}

	private static final class Fixture {
		private static final PlacementState FULL =
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
		private final String fingerprint = "worker-pool-cycle-" + System.identityHashCode(this);
		private final Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		private final Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		private final List<CandidateRuleFact> candidates = new ArrayList<>();
		private final List<CompiledInputEdgeFact> edges = new ArrayList<>();
		private final List<Constraint> constraints = new ArrayList<>();
		private final Map<CompiledHopKey,Privacy> privacy = new IdentityHashMap<>();
		private int ordinal;

		private Ref source(String name, String endpoint) {
			DurableAnchorKey anchor = new DurableAnchorKey(name, FType.FULL, List.of(
				new AnchorPartition(endpoint, List.of(0L, 0L), List.of(4L, 2L))));
			return add(name, transientRead(name), NodeKind.TRANSIENT_READ, anchor);
		}

		private Ref read(String name) {
			return add(name, transientRead(name), NodeKind.TRANSIENT_READ, null);
		}

		private Ref append(String name, Ref left, Ref right) {
			Ref result = add(name, new BinaryOp(name, DataType.MATRIX, ValueType.FP64,
				OpOp2.CBIND, left.hop, right.hop), NodeKind.OPERATION, null);
			edges.add(new CompiledInputEdgeFact(left.key, result.key, 0));
			edges.add(new CompiledInputEdgeFact(right.key, result.key, 1));
			return result;
		}

		private Ref unary(String name, Ref input) {
			Ref result = add(name, new UnaryOp(name, DataType.MATRIX, ValueType.FP64,
				OpOp1.EXP, input.hop), NodeKind.OPERATION, null);
			edges.add(new CompiledInputEdgeFact(input.key, result.key, 0));
			candidate(result, FType.FULL, CandidateInputState.present(FType.FULL));
			return result;
		}

		private Ref functionOutput(String name) {
			FunctionOp call = new FunctionOp(FunctionType.DML, "main", name,
				new String[] {"unused"}, List.of(new LiteralOp(1L)), new String[] {"Y"}, true);
			return add(name, call, NodeKind.FUNCTION_OUTPUT, null);
		}

		private void functionOutput(Ref read, Ref boundary, Ref returned) {
			constraints.add(new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT,
				returned.key, boundary.key, 0, "function-result:Y"));
			constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT,
				boundary.key, read.key, 0, "cfg-function-output-value:Y"));
		}

		private Ref update(String name, Ref headerRead, Ref appendedColumn,
			CandidateInputState left, CandidateInputState right) {
			Ref append = append(name + "-append", headerRead, appendedColumn);
			candidate(append, FType.FULL, left, right);
			Ref write = write(name + "-write", append);
			candidate(write, FType.FULL, CandidateInputState.present(FType.FULL));
			return write;
		}

		private Ref write(String name, Ref input) {
			Ref result = add(name, new DataOp(name, DataType.MATRIX, ValueType.FP64,
				input.hop, OpOpData.TRANSIENTWRITE, name), NodeKind.LOOP_PHI, null);
			edges.add(new CompiledInputEdgeFact(input.key, result.key, 0));
			return result;
		}

		private void candidate(Ref owner, FType outputFType, CandidateInputState... inputs) {
			CandidateRuleKey key = new CandidateRuleKey(owner.key, List.of(inputs));
			PlacementState output = new PlacementState(ExecType.FED, FederatedOutput.FOUT,
				outputFType, false);
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(output, false), outputFType);
			candidates.add(new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
					FederatedOutput.FOUT, outputFType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(outputFType), ""), List.of(emission), ""));
		}

		private void definitions(Ref read, Ref... definitions) {
			Node original = nodes.get(read.key);
			List<String> predecessorVersions = java.util.Arrays.stream(definitions)
				.map(definition -> "cfg-definition:"
					+ nodes.get(definition.key).valueVersion().cfgReferenceSignature())
				.toList();
			ValueVersionKey value = new ValueVersionKey(fingerprint,
				original.valueVersion().lexicalVariable(), original.valueVersion().definingControlRegion(),
				original.valueVersion().definitionOrdinal(), original.valueVersion().versionKind(),
				predecessorVersions);
			nodes.put(read.key, new Node(read.key, original.kind(), value, original.emittedWork(),
				original.legalAlternatives(), original.exclusions(), original.anchors()));
		}

		private void addPredecessor(Ref read, String predecessor) {
			Node original = nodes.get(read.key);
			List<String> predecessors = new ArrayList<>(original.valueVersion().predecessorVersions());
			predecessors.add(predecessor);
			ValueVersionKey value = new ValueVersionKey(fingerprint,
				original.valueVersion().lexicalVariable(), original.valueVersion().definingControlRegion(),
				original.valueVersion().definitionOrdinal(), original.valueVersion().versionKind(), predecessors);
			nodes.put(read.key, new Node(read.key, original.kind(), value, original.emittedWork(),
				original.legalAlternatives(), original.exclusions(), original.anchors()));
		}

		private void functionInput(Ref read, Ref argument) {
			Ref boundary = add("function-input-boundary", transientRead("function-input-boundary"),
				NodeKind.FUNCTION_INPUT, null);
			constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, argument.key, boundary.key, 0,
				"function-argument:X"));
			constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT, boundary.key, read.key, 0,
				"function-formal-input"));
			addPredecessor(read, "cfg-function-input:ns:X");
		}

		private void missingFunctionInput(Ref read) {
			Ref boundary = add("missing-function-input-boundary",
				transientRead("missing-function-input-boundary"), NodeKind.FUNCTION_INPUT, null);
			constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT, boundary.key, read.key, 0,
				"function-formal-input"));
		}

		private void privacy(Ref ref, Privacy value) {
			privacy.put(ref.key, value);
		}

		@SuppressWarnings("unchecked")
		private Set<DurableAnchorKey> resolve(Ref target, FType fType) throws Exception {
			Class<?> resolverClass = java.util.Arrays.stream(
				NeutralPlacementGraphBuilder.class.getDeclaredClasses())
				.filter(type -> type.getSimpleName().equals("WorkerPoolAnchorResolver"))
				.findFirst().orElseThrow();
			Constructor<?> constructor = resolverClass.getDeclaredConstructor(Map.class, Map.class,
				List.class, List.class, Collection.class, Map.class, Map.class, Map.class);
			constructor.setAccessible(true);
			Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> edgesByConsumer =
				new IdentityHashMap<>();
			for(CompiledInputEdgeFact edge : edges)
				edgesByConsumer.computeIfAbsent(edge.consumer(), ignored -> new LinkedHashMap<>())
					.put(edge.inputPosition(), edge);
			Object resolver = constructor.newInstance(nodes, edgesByConsumer, candidates, List.of(),
				constraints, origins, shapes, privacy);
			Method resolve = resolverClass.getDeclaredMethod("resolve", CompiledHopKey.class, FType.class);
			resolve.setAccessible(true);
			return (Set<DurableAnchorKey>)resolve.invoke(resolver, target.key, fType);
		}

		private Ref add(String name, Hop hop, NodeKind kind, DurableAnchorKey anchor) {
			ControlRegionKey region = new ControlRegionKey(fingerprint, "main",
				List.of("main/" + ordinal), "main", "compiled");
			CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled",
				region, name + '@' + ordinal, name);
			ValueVersionKey value = new ValueVersionKey(fingerprint, name, region, ordinal++,
				VersionKind.ORDINARY, List.of());
			Node node = new Node(key, kind, value, true, List.of(FULL), List.of(),
				anchor == null ? List.of() : List.of(anchor));
			nodes.put(key, node);
			origins.put(key, hop);
			shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 4, 2));
			return new Ref(key, hop, anchor);
		}

		private static DataOp transientRead(String name) {
			return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, 4, 2, 8, 1000);
		}
	}

	private record Ref(CompiledHopKey key, Hop hop, DurableAnchorKey anchor) { }
}

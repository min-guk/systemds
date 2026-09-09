/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact scalar proofs must cover the complete qualified call graph, not literal syntax. */
public class PlacementNestedScalarBindingTest {

	@Test
	public void exactSelectorKeepsLocalFunctionReturnLocal() throws Exception {
		String script = "choose=function(matrix[double] A, int selector) return(matrix[double] out) {"
			+ "out=matrix(0,rows=4,cols=2);if(selector==2){out=A+1;}}\n"
			+ "A=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(4,2)));\n"
			+ "flag=0;first=choose(A,flag);second=choose(A,flag);print(sum(first+second));\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var outputs = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT).toList();
		Assert.assertEquals("Both real call-return boundaries must be checked", 2, outputs.size());
		for(var output : outputs) {
			Assert.assertFalse(output.legalAlternatives().isEmpty());
			Assert.assertTrue("Exact local arm must not acquire a remote return state: " + output,
				output.legalAlternatives().stream().allMatch(state -> state.execType() == ExecType.CP
					&& state.output() == FederatedOutput.LOUT));
		}
	}

	@Test
	public void nestedAliasesAndTwoSameValueCallsRetainExactSelectors() {
		Fixture f = new Fixture();
		LiteralOp two = f.add(new LiteralOp(2L));
		f.call("ns", "outer", two);
		f.call("ns", "outer", two);
		DataOp outer = f.formal("ns::outer");
		DataOp alias = f.add(new DataOp("forwarded", DataType.SCALAR, ValueType.INT64,
			outer, OpOpData.TRANSIENTWRITE, "forwarded"));
		DataOp read = f.add(scalar("forwarded"));
		f.definitions.put(read, Set.of(f.hops.indexOf(alias)));
		f.call("ns", "inner", read);
		f.call("ns", "inner", read);
		DataOp inner = f.formal("ns::inner");
		Assert.assertFalse("The regression must not rely on a direct literal actual",
			new FunctionCallSizeInfo(f.graph()).isSafeLiteral("ns::inner", 0));
		assertExact(f.infer(), inner, "2");
	}

	@Test
	public void equalFunctionNamesInDifferentNamespacesDoNotJoinActuals() {
		Fixture f = new Fixture();
		f.call("first", "choose", f.add(new LiteralOp(2L)));
		f.call("second", "choose", f.add(new LiteralOp(0L)));
		DataOp first = f.formal("first::choose");
		DataOp second = f.formal("second::choose");
		var facts = f.infer();
		assertExact(facts, first, "2");
		assertExact(facts, second, "0");
	}

	@Test
	public void differentAndUnknownCallArgumentsRemainUnknown() {
		for(boolean unknown : List.of(false, true)) {
			Fixture f = new Fixture();
			f.call("ns", "choose", f.add(new LiteralOp(2L)));
			f.call("ns", "choose", unknown ? f.add(scalar("external")) : f.add(new LiteralOp(3L)));
			DataOp formal = f.formal("ns::choose");
			Assert.assertTrue(f.infer().scalars().get(formal).isUnknown());
		}
	}

	@Test
	public void missingCallOrActualCannotCertifyACompleteBinding() {
		for(boolean missingCall : List.of(false, true)) {
			Fixture f = new Fixture();
			LiteralOp two = f.add(new LiteralOp(2L));
			f.call("ns", "choose", two);
			// The supplied call graph contains evidence absent from the occurrence closure.
			FunctionOp second = f.call("ns", "choose", missingCall ? two : new LiteralOp(2L));
			if(missingCall)
				f.hops.remove(second);
			DataOp formal = f.formal("ns::choose");
			Assert.assertTrue("Partial call coverage is not an exact scalar proof",
				f.infer().scalars().get(formal).isUnknown());
		}
	}

	@Test
	public void missingFormalNameInvalidatesTheBinding() {
		Fixture f = new Fixture();
		LiteralOp two = f.add(new LiteralOp(2L));
		f.call("ns", "choose", two);
		f.call("ns", "choose", two).setInputVariableNames(new String[] {"other"});
		DataOp formal = f.formal("ns::choose");
		Assert.assertTrue(f.infer().scalars().get(formal).isUnknown());
	}

	@Test
	public void duplicateFormalNamesInvalidateTheBinding() {
		Fixture f = new Fixture();
		LiteralOp two = f.add(new LiteralOp(2L));
		FunctionOp call = f.call("ns", "choose", two);
		call.getInput().add(two);
		call.setInputVariableNames(new String[] {"selector", "selector"});
		DataOp formal = f.formal("ns::choose");
		Assert.assertTrue(f.infer().scalars().get(formal).isUnknown());
	}

	@Test
	public void recursiveCallGraphCannotCertifyAnExternalSeed() {
		for(boolean indirect : List.of(false, true)) {
			Fixture f = new Fixture();
			f.call("ns", "choose", f.add(new LiteralOp(2L)));
			DataOp formal = f.formal("ns::choose");
			f.recursive.add("ns::choose");
			if(indirect) {
				DataOp intermediate = f.formal("ns::relay");
				f.call("ns", "relay", formal);
				f.call("ns", "choose", intermediate);
				f.recursive.add("ns::relay");
			}
			else
				f.call("ns", "choose", formal);
			Assert.assertTrue("Exact external seed does not certify recursive provenance",
				f.infer().scalars().get(formal).isUnknown());
		}
	}

	@Test
	public void defaultNamespaceUsesItsQualifiedCallGraphKey() {
		Fixture f = new Fixture();
		f.call(DMLProgram.DEFAULT_NAMESPACE, "choose", f.add(new LiteralOp(0L)));
		DataOp formal = f.formal("choose");
		assertExact(f.infer(), formal, "0");
	}

	@Test
	public void ungroundedRecursiveForwardingNeverBecomesExact() {
		Fixture f = new Fixture();
		DataOp formal = f.formal("ns::recurse");
		f.call("ns", "recurse", formal);
		Assert.assertTrue(f.infer().scalars().get(formal).isUnknown());
	}

	@Test
	public void lateUnknownArgumentInvalidatesEarlierExactProof() {
		Fixture f = new Fixture();
		LiteralOp two = f.add(new LiteralOp(2L));
		f.call("ns", "choose", two);
		DataOp formal = f.formal("ns::choose");
		Hop delayed = f.add(scalar("unknown"));
		for(int index = 0; index < 24; index++)
			delayed = f.add(new DataOp("delayed" + index, DataType.SCALAR, ValueType.INT64,
				delayed, OpOpData.TRANSIENTWRITE, "delayed" + index));
		f.call("ns", "choose", delayed);
		Assert.assertTrue(f.infer().scalars().get(formal).isUnknown());
	}

	@Test
	public void locallyShadowedFormalKeepsItsDefinitionInsteadOfCallerValue() {
		Fixture f = new Fixture();
		f.call("ns", "choose", f.add(new LiteralOp(2L)));
		LiteralOp zero = f.add(new LiteralOp(0L));
		DataOp local = f.add(scalar("selector"));
		f.namespaces.put(local, "ns::choose");
		f.definitions.put(local, Set.of(f.hops.indexOf(zero)));
		assertExact(f.infer(), local, "0");
	}

	private static void assertExact(PlacementAbstractShapeAnalysis.HopFacts facts, Hop hop, String expected) {
		var scalar = facts.scalars().get(hop);
		Assert.assertTrue("Expected an all-call exact binding for " + hop.getName(), scalar.isExact());
		Assert.assertEquals(expected, scalar.literal().canonicalValue());
	}

	private static DataOp scalar(String name) {
		return new DataOp(name, DataType.SCALAR, ValueType.INT64, OpOpData.TRANSIENTREAD,
			name, 0, 0, -1, 1000);
	}

	private static final class Fixture {
		final List<Hop> hops = new ArrayList<>();
		final Map<Hop,String> namespaces = new IdentityHashMap<>();
		final Map<Hop,Set<Integer>> definitions = new IdentityHashMap<>();
		final Set<Hop> formals = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		final Set<String> recursive = new java.util.HashSet<>();
		final Map<String,List<FunctionOp>> calls = new LinkedHashMap<>();

		<T extends Hop> T add(T hop) { hops.add(hop); return hop; }

		DataOp formal(String key) {
			DataOp read = add(scalar("selector"));
			namespaces.put(read, key);
			formals.add(read);
			return read;
		}

		FunctionOp call(String namespace, String name, Hop input) {
			FunctionOp call = add(new FunctionOp(FunctionType.DML, namespace, name,
				new String[] {"selector"}, List.of(input), new String[] {"out"}, false));
			calls.computeIfAbsent(call.getFunctionKey(), ignored -> new ArrayList<>()).add(call);
			return call;
		}

		FunctionCallGraph graph() {
			return new FunctionCallGraph(new DMLProgram()) {
				@Override public Set<String> getReachableFunctions() { return calls.keySet(); }
				@Override public List<FunctionOp> getFunctionCalls(String key) { return calls.get(key); }
				@Override public boolean isRecursiveFunction(String key) { return recursive.contains(key); }
			};
		}

		PlacementAbstractShapeAnalysis.HopFacts infer() {
			FunctionCallGraph graph = graph();
			return PlacementAbstractShapeAnalysis.inferOriginalOccurrences(hops,
				hops.stream().map(hop -> namespaces.getOrDefault(hop, "main")).toList(),
				hops.stream().map(hop -> definitions.getOrDefault(hop, Set.of())).toList(),
				hops.stream().map(formals::contains).toList(), graph, new FunctionCallSizeInfo(graph));
		}
	}
}

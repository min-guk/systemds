/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.test.component.federated.placement.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCut;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Pre-patch Layer-2 contract for a bijective neutral projection and graph-free logging. */
public class MinStLayer2ProjectionLoggerContractTest {
	@Test
	public void selectedReceiptsAreABijectiveLegalProjectionOfTheNeutralGraph() throws Exception {
		Method select;
		try {
			select = MinStPlacementAdapter.class.getMethod("select", PlacementAnalysis.class,
				MinStPlacementInput.class);
		}
		catch(NoSuchMethodException missing) {
			Assert.fail("MINST_LAYER2_SELECT_BOUNDARY_MISSING");
			return;
		}

		for(String fixture : List.of("B-01", "B-07", "B-09", "B-16")) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			MinStPlacementInput input = new FederatedPlanMinSTCut()
				.rewriteProgram(program, null, null, analysis);
			MinStPlacementAdapter.Selection selection = invoke(select, analysis, input);

			List<CompiledHopKey> expected = analysis.graph().nodes().stream()
				.map(NeutralPlacementGraph.Node::key).toList();
			List<CompiledHopKey> actual = selection.selectedReceipts().stream()
				.map(MinStPlacementAdapter.SelectedReceipt::planningKey).toList();
			Assert.assertEquals(fixture + " receipt cardinality", expected.size(), actual.size());
			Assert.assertEquals(fixture + " duplicate receipt key", actual.size(), new HashSet<>(actual).size());
			Assert.assertEquals(fixture + " receipt key universe", new HashSet<>(expected), new HashSet<>(actual));

			for(MinStPlacementAdapter.SelectedReceipt receipt : selection.selectedReceipts()) {
				NeutralPlacementGraph.Node node = analysis.graph().node(receipt.planningKey()).orElseThrow();
				Assert.assertSame(fixture + " planning Hop identity", analysis.hop(node.key()).orElseThrow(),
					receipt.planningHop());
				if(!node.emittedWork()) {
					Assert.assertNull(fixture + " trace exec", receipt.execType());
					Assert.assertEquals(fixture + " trace output", FederatedOutput.NONE, receipt.output());
				}
				else
					Assert.assertTrue(fixture + " selected state is not neutral-legal: " + node.key(),
						node.legalAlternatives().stream().anyMatch(state -> state.execType() == receipt.execType()
							&& state.output() == receipt.output()));
			}
			assertAllEndpointsUseNeutralKeys(fixture, analysis.graph(), new HashSet<>(expected));
		}
	}

	@Test
	public void loggerPublicAndProtectedSurfaceIsGraphFree() {
		for(Method method : FederatedPlannerLogger.class.getDeclaredMethods()) {
			int modifiers = method.getModifiers();
			if(!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers))
				continue;
			assertGraphFree(method.getGenericReturnType());
			for(Type parameter : method.getGenericParameterTypes())
				assertGraphFree(parameter);
		}
	}

	private static MinStPlacementAdapter.Selection invoke(Method select, PlacementAnalysis analysis,
		MinStPlacementInput input) throws Exception {
		try {
			return (MinStPlacementAdapter.Selection) select.invoke(new MinStPlacementAdapter(), analysis, input);
		}
		catch(InvocationTargetException failure) {
			Throwable cause = failure.getCause();
			if(cause instanceof Exception exception)
				throw exception;
			if(cause instanceof Error error)
				throw error;
			throw failure;
		}
	}

	private static void assertAllEndpointsUseNeutralKeys(String fixture, NeutralPlacementGraph graph,
		Set<CompiledHopKey> keys) {
		for(NeutralPlacementGraph.Constraint constraint : graph.constraints()) {
			Assert.assertTrue(fixture + " foreign left constraint endpoint", keys.contains(constraint.left()));
			Assert.assertTrue(fixture + " foreign right constraint endpoint", keys.contains(constraint.right()));
		}
		for(NeutralPlacementGraph.RelocationAction action : graph.relocationActions()) {
			Assert.assertTrue(fixture + " foreign relocation consumer",
				keys.containsAll(action.key().compatibleConsumers()));
			for(var obligation : action.obligations())
				Assert.assertTrue(fixture + " foreign obligation consumer", keys.contains(obligation.consumer()));
		}
	}

	private static void assertGraphFree(Type type) {
		String name = type.getTypeName();
		Assert.assertFalse("MINST_LAYER2_GRAPH_TYPED_LOGGER:" + name,
			name.contains("FederatedPlanMinSTGraph") || name.endsWith(".Vertex"));
	}
}

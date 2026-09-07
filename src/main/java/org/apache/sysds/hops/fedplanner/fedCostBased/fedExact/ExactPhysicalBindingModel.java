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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthority;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Exact coordinate transformation from packed physical alternatives to independent
 * operator ({@code X}) and ordered-use authority ({@code Y}) variables.
 *
 * <p>The original alternatives remain the admitted complete-plan catalog.  Every legal
 * X/Y product maps to exactly one graph-owned catalog row; products absent from that
 * catalog are rejected by an admission factor.  Original legality and cost factors are
 * pulled back through this map, so shared-cost decompositions and their auxiliaries keep
 * precisely their original semantics.</p>
 */
final class ExactPhysicalBindingModel {
	enum OwnerKind { COMPILED, LOGICAL_TRANSIENT, LOGICAL_FUNCTION }
	enum SupplyKind { NATIVE_LOUT, LOCAL_COPY, DIRECT_FOUT, RELOCATION }

	/** Stable identity of one authority-bearing occurrence in a consumer input. */
	record BindingUse(OwnerKind ownerKind, CompiledHopKey sourceDecision,
		CompiledHopKey consumer, int inputPosition, int sourceOccurrence) {
		BindingUse {
			Objects.requireNonNull(ownerKind, "ownerKind");
			Objects.requireNonNull(sourceDecision, "sourceDecision");
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0 || sourceOccurrence < 0)
				throw new IllegalArgumentException("EXACT_BINDING_USE_INVALID");
		}
	}

	/** A Y value. Inactive is required for uses conditional on an X alternative. */
	record BindingValue(boolean active, SupplyKind supplyKind, InputAuthority authority) {
		BindingValue {
			if(active != (authority != null) || active != (supplyKind != null))
				throw new IllegalArgumentException("EXACT_BINDING_VALUE_INVALID");
		}
		static BindingValue inactive() { return new BindingValue(false, null, null); }
		static BindingValue selected(SupplyKind supplyKind, InputAuthority authority) {
			return new BindingValue(true, Objects.requireNonNull(supplyKind, "supplyKind"),
				Objects.requireNonNull(authority, "authority"));
		}
	}

	/** Explicit executable binding retained after solving. */
	record SelectedBinding(BindingUse use, SupplyKind supplyKind, InputAuthority authority) {
		SelectedBinding {
			Objects.requireNonNull(use, "use");
			Objects.requireNonNull(supplyKind, "supplyKind");
			Objects.requireNonNull(authority, "authority");
			if(authority.inputPosition() != use.inputPosition()
				|| authority.sourceDecision() != null
					&& authority.sourceDecision() != use.sourceDecision())
				throw new IllegalArgumentException("EXACT_SELECTED_BINDING_IDENTITY_INVALID");
		}
		CompiledHopKey consumer() { return use.consumer(); }
		int inputPosition() { return use.inputPosition(); }
		CompiledHopKey sourceDecision() { return use.sourceDecision(); }
		List<InputAuthority> authorities() { return List.of(authority); }
	}

	record OperatorChoice(Alternative representative) {
		OperatorChoice { Objects.requireNonNull(representative, "representative"); }
	}
	record OperatorDomain(Node node, Variable variable, List<OperatorChoice> choices) {
		OperatorDomain {
			Objects.requireNonNull(node, "node");
			Objects.requireNonNull(variable, "variable");
			choices = List.copyOf(choices);
			if(choices.isEmpty() || choices.size() != variable.domainSize())
				throw new IllegalArgumentException("EXACT_OPERATOR_DOMAIN_INVALID");
		}
	}
	record BindingDomain(BindingUse use, Variable variable, List<BindingValue> choices) {
		BindingDomain {
			Objects.requireNonNull(use, "use");
			Objects.requireNonNull(variable, "variable");
			choices = List.copyOf(choices);
			if(choices.isEmpty() || choices.size() != variable.domainSize())
				throw new IllegalArgumentException("EXACT_BINDING_DOMAIN_INVALID");
		}
	}

	private record OperatorKey(CompiledHopKey decision, PlacementState state,
		ExactPhysicalModel.AuthorityKind authorityKind, CandidateRuleFact candidateRule,
		CandidateEmissionFact candidateEmission, CandidateRuleFact executionRule,
		CandidateEmissionFact executionEmission, DurableAnchorKey durableAnchor,
		RelocationAction relocationAction, DerivedFoutMaterializationAction derivedFoutAction,
		List<CandidateInputState> orderedInputs) {
		static OperatorKey of(Alternative alternative) {
			return new OperatorKey(alternative.decision(), alternative.state(),
				alternative.authorityKind(), alternative.candidateRule(), alternative.candidateEmission(),
				alternative.executionRule(), alternative.executionEmission(), alternative.durableAnchor(),
				alternative.relocationAction(), alternative.derivedFoutAction(), alternative.orderedInputs());
		}
	}

	private static final class Coordinates {
		private final int[] values;
		private Coordinates(int[] values) { this.values = values.clone(); }
		@Override public boolean equals(Object other) {
			return other instanceof Coordinates that && Arrays.equals(values, that.values);
		}
		@Override public int hashCode() { return Arrays.hashCode(values); }
	}

	private record DecisionCoordinates(DecisionDomain packed, OperatorDomain operator,
		List<BindingDomain> bindings, Map<Coordinates,Integer> packedByCoordinates,
		List<int[]> coordinatesByPacked) { }

	private final ExactPhysicalModel packedModel;
	private final List<OperatorDomain> operatorDomains;
	private final List<BindingDomain> bindingDomains;
	private final List<Variable> variables;
	private final List<DecisionCoordinates> decisions;
	private final Map<Variable,DecisionCoordinates> byPackedVariable;
	private final Map<Variable,Integer> jointVariableIndex;
	private final Map<CompiledHopKey,DecisionCoordinates> byDecision;

	private ExactPhysicalBindingModel(ExactPhysicalModel packedModel,
		List<DecisionCoordinates> decisions) {
		this.packedModel = packedModel;
		this.decisions = List.copyOf(decisions);
		this.operatorDomains = decisions.stream().map(DecisionCoordinates::operator).toList();
		this.bindingDomains = decisions.stream().flatMap(decision -> decision.bindings().stream()).toList();
		List<Variable> ordered = new ArrayList<>();
		operatorDomains.forEach(domain -> ordered.add(domain.variable()));
		bindingDomains.forEach(domain -> ordered.add(domain.variable()));
		this.variables = List.copyOf(ordered);
		Map<Variable,DecisionCoordinates> packedIndex = new IdentityHashMap<>();
		for(DecisionCoordinates decision : decisions)
			if(packedIndex.put(decision.packed().variable(), decision) != null)
				throw new IllegalArgumentException("EXACT_BINDING_PACKED_VARIABLE_DUPLICATE");
		this.byPackedVariable = Collections.unmodifiableMap(packedIndex);
		Map<Variable,Integer> jointIndex = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			if(jointIndex.put(variables.get(index), index) != null)
				throw new IllegalArgumentException("EXACT_BINDING_VARIABLE_DUPLICATE");
		this.jointVariableIndex = Collections.unmodifiableMap(jointIndex);
		Map<CompiledHopKey,DecisionCoordinates> decisionIndex = new IdentityHashMap<>();
		for(DecisionCoordinates decision : decisions)
			decisionIndex.put(decision.packed().node().key(), decision);
		this.byDecision = decisionIndex;
	}

	static ExactPhysicalBindingModel build(ExactPhysicalModel model) {
		Objects.requireNonNull(model, "model");
		List<DecisionCoordinates> decisions = new ArrayList<>();
		Map<CompiledHopKey,List<BindingUse>> uses = bindingUses(model.analysis());
		for(DecisionDomain packed : model.domains())
			decisions.add(buildDecision(packed, uses.getOrDefault(packed.node().key(), List.of())));
		return new ExactPhysicalBindingModel(model, decisions);
	}

	List<Variable> variables() { return variables; }
	List<OperatorDomain> operatorDomains() { return operatorDomains; }
	List<BindingDomain> bindingDomains() { return bindingDomains; }
	long indexedCatalogRows() {
		return decisions.stream().mapToLong(decision -> decision.packedByCoordinates().size()).sum();
	}

	/** Adds catalog admission factors, then pulls every caller factor back to X/Y. */
	List<Factor> translateFactors(List<Factor> originalFactors) {
		Objects.requireNonNull(originalFactors, "originalFactors");
		List<Factor> translated = new ArrayList<>();
		for(DecisionCoordinates decision : decisions) {
			List<Variable> scope = decisionScope(decision);
			translated.add(Factor.lazy(scope, values ->
				lookupPacked(decision, values) < 0 ? Double.POSITIVE_INFINITY : 0d));
		}
		for(BindingDomain domain : bindingDomains) {
			DecisionCoordinates source = byDecision.get(domain.use().sourceDecision());
			if(source == null)
				throw new IllegalArgumentException("EXACT_BINDING_SOURCE_DOMAIN_MISSING");
			translated.add(Factor.lazy(List.of(source.operator().variable(), domain.variable()), values -> {
				BindingValue binding = domain.choices().get(values[1]);
				if(!binding.active())
					return 0d;
				boolean copy = localCopyEligible(source.operator().choices().get(values[0]).representative());
				return binding.supplyKind() == SupplyKind.LOCAL_COPY ? (copy ? 0d : Double.POSITIVE_INFINITY)
					: binding.supplyKind() == SupplyKind.NATIVE_LOUT
						? (copy ? Double.POSITIVE_INFINITY : 0d) : 0d;
			}));
		}
		for(Factor original : originalFactors)
			translated.add(translateFactor(original));
		return List.copyOf(translated);
	}

	/** X/Y variables followed by the original auxiliary variables in their original order. */
	List<Variable> solverVariables(List<Variable> originalWithAuxiliaries) {
		Objects.requireNonNull(originalWithAuxiliaries, "originalWithAuxiliaries");
		Map<Variable,Boolean> seen = new IdentityHashMap<>();
		for(Variable variable : originalWithAuxiliaries) {
			Objects.requireNonNull(variable, "variable");
			if(seen.put(variable, Boolean.TRUE) != null)
				throw new IllegalArgumentException("EXACT_BINDING_SOLVER_VARIABLE_DUPLICATE");
			for(Variable packed : byPackedVariable.keySet())
				if(variable != packed && variable.equals(packed))
					throw new IllegalArgumentException("EXACT_BINDING_FOREIGN_PACKED_VARIABLE");
		}
		for(Variable packed : byPackedVariable.keySet())
			if(!seen.containsKey(packed))
				throw new IllegalArgumentException("EXACT_BINDING_PACKED_VARIABLE_MISSING");
		List<Variable> result = new ArrayList<>(variables);
		for(Variable variable : originalWithAuxiliaries)
			if(!byPackedVariable.containsKey(variable)) {
				if(jointVariableIndex.containsKey(variable))
					throw new IllegalArgumentException("EXACT_BINDING_AUXILIARY_COLLISION");
				result.add(variable);
			}
		return List.copyOf(result);
	}

	/** Reconstructs packed decision indexes from a complete X/Y prefix assignment. */
	List<Integer> reconstruct(List<Integer> jointAssignment) {
		validateJointAssignment(jointAssignment);
		validateSupplyCompatibility(jointAssignment);
		List<Integer> packed = new ArrayList<>(decisions.size());
		for(DecisionCoordinates decision : decisions) {
			int selected = lookupPacked(decision, valuesForDecision(decision, jointAssignment));
			if(selected < 0)
				throw new IllegalArgumentException("EXACT_BINDING_ASSIGNMENT_NOT_ADMITTED|decision="
					+ decision.packed().node().key().normalizedSignature());
			packed.add(selected);
		}
		return List.copyOf(packed);
	}

	/** Explicit active supplies selected by a complete admitted X/Y assignment. */
	List<SelectedBinding> bindings(List<Integer> jointAssignment) {
		validateJointAssignment(jointAssignment);
		reconstruct(jointAssignment);
		List<SelectedBinding> selected = new ArrayList<>();
		for(BindingDomain domain : bindingDomains) {
			BindingValue value = domain.choices().get(jointAssignment.get(
				jointVariableIndex.get(domain.variable())));
			if(value.active())
				selected.add(new SelectedBinding(domain.use(), value.supplyKind(), value.authority()));
		}
		return List.copyOf(selected);
	}

	/** Encodes one complete packed catalog assignment into its unique X/Y coordinates. */
	List<Integer> encode(List<Integer> packedAssignment) {
		Objects.requireNonNull(packedAssignment, "packedAssignment");
		if(packedAssignment.size() != decisions.size())
			throw new IllegalArgumentException("EXACT_BINDING_PACKED_ASSIGNMENT_SIZE_MISMATCH");
		int[] result = new int[variables.size()];
		for(int decisionIndex = 0; decisionIndex < decisions.size(); decisionIndex++) {
			DecisionCoordinates decision = decisions.get(decisionIndex);
			Integer packedValue = packedAssignment.get(decisionIndex);
			if(packedValue == null || packedValue < 0
				|| packedValue >= decision.coordinatesByPacked().size())
				throw new IllegalArgumentException("EXACT_BINDING_PACKED_ASSIGNMENT_VALUE_INVALID");
			int[] coordinates = decision.coordinatesByPacked().get(packedValue);
			List<Variable> scope = decisionScope(decision);
			for(int index = 0; index < scope.size(); index++)
				result[jointVariableIndex.get(scope.get(index))] = coordinates[index];
		}
		Map<CompiledHopKey,Integer> packedByDecision = new IdentityHashMap<>();
		for(int index = 0; index < decisions.size(); index++)
			packedByDecision.put(decisions.get(index).packed().node().key(), packedAssignment.get(index));
		for(BindingDomain binding : bindingDomains) {
			int coordinate = jointVariableIndex.get(binding.variable());
			BindingValue encoded = binding.choices().get(result[coordinate]);
			if(encoded.active() && encoded.supplyKind() == SupplyKind.NATIVE_LOUT) {
				DecisionCoordinates source = byDecision.get(binding.use().sourceDecision());
				Integer sourceValue = packedByDecision.get(binding.use().sourceDecision());
				if(sourceValue != null && localCopyEligible(source.packed().alternatives().get(sourceValue))) {
					int localCopy = binding.choices().indexOf(
						BindingValue.selected(SupplyKind.LOCAL_COPY, encoded.authority()));
					if(localCopy < 0)
						throw new IllegalArgumentException("EXACT_BINDING_LOCAL_COPY_VALUE_MISSING");
					result[coordinate] = localCopy;
				}
			}
		}
		return Arrays.stream(result).boxed().toList();
	}

	private Factor translateFactor(Factor original) {
		LinkedHashSet<Variable> translatedScope = new LinkedHashSet<>();
		for(Variable variable : original.scope()) {
			DecisionCoordinates decision = byPackedVariable.get(variable);
			if(decision == null)
				for(Variable packed : byPackedVariable.keySet())
					if(variable.equals(packed))
						throw new IllegalArgumentException("EXACT_BINDING_FACTOR_FOREIGN_PACKED_VARIABLE");
			if(decision == null)
				translatedScope.add(variable);
			else
				translatedScope.addAll(decisionScope(decision));
		}
		List<Variable> scope = List.copyOf(translatedScope);
		Map<Variable,Integer> positions = new LinkedHashMap<>();
		for(int index = 0; index < scope.size(); index++)
			positions.put(scope.get(index), index);
		return Factor.lazy(scope, values -> {
			int[] originalValues = new int[original.scope().size()];
			for(int index = 0; index < original.scope().size(); index++) {
				Variable originalVariable = original.scope().get(index);
				DecisionCoordinates decision = byPackedVariable.get(originalVariable);
				if(decision == null)
					originalValues[index] = values[positions.get(originalVariable)];
				else {
					List<Variable> decisionScope = decisionScope(decision);
					int[] coordinates = new int[decisionScope.size()];
					for(int coordinate = 0; coordinate < decisionScope.size(); coordinate++)
						coordinates[coordinate] = values[positions.get(decisionScope.get(coordinate))];
					int packed = lookupPacked(decision, coordinates);
					if(packed < 0)
						return Double.POSITIVE_INFINITY;
					originalValues[index] = packed;
				}
			}
			return original.cost(originalValues);
		});
	}

	private static DecisionCoordinates buildDecision(DecisionDomain packed, List<BindingUse> admittedUses) {
		Map<OperatorKey,Integer> operatorIndexes = new LinkedHashMap<>();
		List<OperatorChoice> operators = new ArrayList<>();
		for(Alternative alternative : packed.alternatives()) {
			OperatorKey key = OperatorKey.of(alternative);
			if(!operatorIndexes.containsKey(key)) {
				operatorIndexes.put(key, operators.size());
				operators.add(new OperatorChoice(alternative));
			}
		}
		Variable x = new Variable("X|" + packed.variable().key(), operators.size());
		OperatorDomain operator = new OperatorDomain(packed.node(), x, operators);

		List<Map<BindingUse,InputAuthority>> rowAuthorities = packed.alternatives().stream()
			.map(alternative -> authoritySlots(admittedUses, alternative.inputAuthorities())).toList();
		LinkedHashSet<BindingUse> uses = new LinkedHashSet<>();
		rowAuthorities.forEach(row -> uses.addAll(row.keySet()));
		List<BindingDomain> bindings = new ArrayList<>();
		for(BindingUse use : uses) {
			List<BindingValue> choices = new ArrayList<>();
			boolean needsInactive = rowAuthorities.stream().anyMatch(row -> !row.containsKey(use));
			if(needsInactive)
				choices.add(BindingValue.inactive());
			for(Map<BindingUse,InputAuthority> row : rowAuthorities) {
				InputAuthority authority = row.get(use);
				if(authority != null) {
					for(SupplyKind kind : supplyKinds(authority)) {
						BindingValue value = BindingValue.selected(kind, authority);
						if(!choices.contains(value)) choices.add(value);
					}
				}
			}
			String source = use.sourceDecision().normalizedSignature();
			Variable y = new Variable("Y|" + packed.variable().key() + "|owner="
				+ use.ownerKind() + "|input="
				+ use.inputPosition() + "|source=" + source + "|occurrence=" + use.sourceOccurrence(),
				choices.size());
			bindings.add(new BindingDomain(use, y, choices));
		}

		Map<Coordinates,Integer> reverse = new LinkedHashMap<>();
		List<int[]> forward = new ArrayList<>();
		for(int packedIndex = 0; packedIndex < packed.alternatives().size(); packedIndex++) {
			Alternative alternative = packed.alternatives().get(packedIndex);
			int[] coordinates = new int[1 + bindings.size()];
			coordinates[0] = operatorIndexes.get(OperatorKey.of(alternative));
			Map<BindingUse,InputAuthority> authorities = rowAuthorities.get(packedIndex);
			for(int binding = 0; binding < bindings.size(); binding++) {
				BindingDomain domain = bindings.get(binding);
				InputAuthority authority = authorities.get(domain.use());
				BindingValue value = authority == null ? BindingValue.inactive()
					: BindingValue.selected(supplyKinds(authority).get(0), authority);
				coordinates[binding + 1] = domain.choices().indexOf(value);
				if(coordinates[binding + 1] < 0)
					throw new IllegalArgumentException("EXACT_BINDING_COORDINATE_VALUE_MISSING");
			}
			if(reverse.put(new Coordinates(coordinates), packedIndex) != null)
				throw new IllegalArgumentException("EXACT_BINDING_CATALOG_COORDINATE_DUPLICATE|decision="
					+ packed.node().key().normalizedSignature());
			forward.add(coordinates);
		}
		return new DecisionCoordinates(packed, operator, List.copyOf(bindings), Map.copyOf(reverse),
			List.copyOf(forward));
	}

	private static Map<BindingUse,InputAuthority> authoritySlots(List<BindingUse> admittedUses,
		List<InputAuthority> authorities) {
		Map<BindingUse,InputAuthority> result = new LinkedHashMap<>();
		for(InputAuthority authority : authorities) {
			List<BindingUse> matching = admittedUses.stream().filter(use ->
				use.inputPosition() == authority.inputPosition()
					&& (authority.sourceDecision() == null
						|| use.sourceDecision() == authority.sourceDecision())).toList();
			if(authority.sourceDecision() != null && matching.size() != 1)
				throw new IllegalArgumentException("EXACT_BINDING_AUTHORITY_USE_AMBIGUOUS");
			for(BindingUse use : matching)
				if(result.put(use, authority) != null)
					throw new IllegalArgumentException("EXACT_BINDING_USE_DUPLICATE");
		}
		return result;
	}

	private static List<SupplyKind> supplyKinds(InputAuthority authority) {
		return switch(authority.kind()) {
			case NATIVE_LOCAL -> List.of(SupplyKind.NATIVE_LOUT, SupplyKind.LOCAL_COPY);
			case DIRECT_FOUT -> List.of(SupplyKind.DIRECT_FOUT);
			case RELOCATION -> List.of(SupplyKind.RELOCATION);
		};
	}

	private static boolean localCopyEligible(Alternative source) {
		if(source.state().execType() != ExecType.FED
			|| source.state().output() != FederatedOutput.FOUT || source.state().fType() == null)
			return false;
		CandidateEmissionFact emission = source.captured()
			? source.candidateEmission() : source.executionEmission();
		return emission == null || !emission.emissionState().derivedFedFout();
	}

	private static Map<CompiledHopKey,List<BindingUse>> bindingUses(PlacementAnalysis analysis) {
		Map<CompiledHopKey,List<BindingUse>> result = new IdentityHashMap<>();
		Map<UseBase,Integer> occurrences = new LinkedHashMap<>();
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics
				.isLatentWdivmmTransposePairBoundary(analysis, edge.producer(), edge.consumer(),
					edge.inputPosition()))
				continue;
			if(analysis.isCoordinatorMetadataOnlyInput(edge))
				continue;
			boolean functionPlaceholder = analysis.graph().node(edge.consumer()).map(node ->
				node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_CALL)
				.orElse(false) && analysis.isDmlFunctionCallBoundary(edge.consumer());
			if(!functionPlaceholder)
				addUse(result, occurrences, OwnerKind.COMPILED, edge.producer(), edge.consumer(),
					edge.inputPosition());
		}
		for(var input : analysis.logicalTransientInputsInCanonicalOrder())
			addUse(result, occurrences, OwnerKind.LOGICAL_TRANSIENT, input.sourceWrite(),
				input.targetRead(), input.logicalPosition());
		for(var input : analysis.logicalFunctionInputsInCanonicalOrder())
			addUse(result, occurrences, OwnerKind.LOGICAL_FUNCTION, input.sourceArgument(),
				input.targetRead(), input.logicalPosition());
		return result;
	}

	private record UseBase(OwnerKind ownerKind, CompiledHopKey source, CompiledHopKey consumer,
		int inputPosition) { }

	private static void addUse(Map<CompiledHopKey,List<BindingUse>> uses,
		Map<UseBase,Integer> occurrences, OwnerKind ownerKind, CompiledHopKey source,
		CompiledHopKey consumer, int inputPosition) {
		UseBase base = new UseBase(ownerKind, source, consumer, inputPosition);
		int occurrence = occurrences.getOrDefault(base, 0);
		occurrences.put(base, occurrence + 1);
		uses.computeIfAbsent(consumer, ignored -> new ArrayList<>()).add(
			new BindingUse(ownerKind, source, consumer, inputPosition, occurrence));
	}

	private static List<Variable> decisionScope(DecisionCoordinates decision) {
		List<Variable> scope = new ArrayList<>(1 + decision.bindings().size());
		scope.add(decision.operator().variable());
		decision.bindings().forEach(binding -> scope.add(binding.variable()));
		return List.copyOf(scope);
	}

	private static int lookupPacked(DecisionCoordinates decision, int[] values) {
		if(values.length != 1 + decision.bindings().size())
			throw new IllegalArgumentException("EXACT_BINDING_DECISION_ASSIGNMENT_SIZE_MISMATCH");
		// LOCAL_COPY is a real solver/certificate choice but normalizes to the legacy
		// NATIVE_LOCAL catalog authority. Source-X compatibility above decides which
		// explicit supply is executable, so the reverse catalog needs only one row.
		int[] canonical = values.clone();
		for(int index = 0; index < decision.bindings().size(); index++) {
			BindingDomain domain = decision.bindings().get(index);
			int selectedIndex = canonical[index + 1];
			if(selectedIndex < 0 || selectedIndex >= domain.choices().size())
				return -1;
			BindingValue selected = domain.choices().get(selectedIndex);
			if(selected.active() && selected.supplyKind() == SupplyKind.LOCAL_COPY) {
				int nativeLocal = domain.choices().indexOf(
					BindingValue.selected(SupplyKind.NATIVE_LOUT, selected.authority()));
				if(nativeLocal < 0)
					return -1;
				canonical[index + 1] = nativeLocal;
			}
		}
		return decision.packedByCoordinates().getOrDefault(new Coordinates(canonical), -1);
	}

	private int[] valuesForDecision(DecisionCoordinates decision, List<Integer> jointAssignment) {
		List<Variable> scope = decisionScope(decision);
		int[] values = new int[scope.size()];
		for(int index = 0; index < scope.size(); index++)
			values[index] = jointAssignment.get(jointVariableIndex.get(scope.get(index)));
		return values;
	}

	private void validateJointAssignment(List<Integer> assignment) {
		Objects.requireNonNull(assignment, "assignment");
		if(assignment.size() != variables.size())
			throw new IllegalArgumentException("EXACT_BINDING_ASSIGNMENT_SIZE_MISMATCH");
		for(int index = 0; index < variables.size(); index++) {
			Integer value = assignment.get(index);
			if(value == null || value < 0 || value >= variables.get(index).domainSize())
				throw new IllegalArgumentException("EXACT_BINDING_ASSIGNMENT_VALUE_INVALID|variable="
					+ variables.get(index).key());
		}
	}

	private void validateSupplyCompatibility(List<Integer> assignment) {
		for(BindingDomain domain : bindingDomains) {
			BindingValue binding = domain.choices().get(
				assignment.get(jointVariableIndex.get(domain.variable())));
			if(!binding.active() || binding.supplyKind() != SupplyKind.NATIVE_LOUT
				&& binding.supplyKind() != SupplyKind.LOCAL_COPY)
				continue;
			DecisionCoordinates source = byDecision.get(domain.use().sourceDecision());
			int sourceX = assignment.get(jointVariableIndex.get(source.operator().variable()));
			boolean copy = localCopyEligible(source.operator().choices().get(sourceX).representative());
			if(copy != (binding.supplyKind() == SupplyKind.LOCAL_COPY))
				throw new IllegalArgumentException("EXACT_BINDING_LOCAL_SUPPLY_INCOMPATIBLE|source="
					+ domain.use().sourceDecision().normalizedSignature());
		}
	}
}

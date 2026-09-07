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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Immutable neutral authority for one selected ordered-use input supply. */
public record InputBindingReceipt(OwnerKind ownerKind, CompiledHopKey sourceOccurrence,
	ValueVersionKey sourceValueVersion, CompiledHopKey consumerOccurrence, int inputPosition,
	int sourceOrdinal, SupplyKind supplyKind, RelocationActionKey relocationAction,
	LocalMaterializationActionKey localMaterializationAction)
	implements Comparable<InputBindingReceipt> {

	public enum OwnerKind { COMPILED, LOGICAL_TRANSIENT, LOGICAL_FUNCTION }
	public enum SupplyKind { NATIVE_LOUT, LOCAL_COPY, DIRECT_FOUT, RELOCATION }

	public InputBindingReceipt {
		Objects.requireNonNull(ownerKind, "ownerKind");
		Objects.requireNonNull(sourceOccurrence, "sourceOccurrence");
		Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
		Objects.requireNonNull(consumerOccurrence, "consumerOccurrence");
		Objects.requireNonNull(supplyKind, "supplyKind");
		if(inputPosition < 0 || sourceOrdinal < 0)
			throw new IllegalArgumentException("INPUT_BINDING_USE_POSITION_INVALID");
		if(supplyKind == SupplyKind.NATIVE_LOUT
			&& (relocationAction != null || localMaterializationAction != null)
			|| supplyKind == SupplyKind.LOCAL_COPY
				&& (relocationAction != null || localMaterializationAction == null)
			|| supplyKind == SupplyKind.DIRECT_FOUT && localMaterializationAction != null
			|| supplyKind == SupplyKind.RELOCATION
				&& (relocationAction == null || localMaterializationAction != null))
			throw new IllegalArgumentException("INPUT_BINDING_SUPPLY_AUTHORITY_INVALID");
		if(localMaterializationAction != null
			&& (localMaterializationAction.sourceOccurrence() != sourceOccurrence
				|| !localMaterializationAction.sourceValueVersion().equals(sourceValueVersion))
			|| relocationAction != null
				&& !relocationAction.sourceValueVersion().equals(sourceValueVersion))
			throw new IllegalArgumentException("INPUT_BINDING_ACTION_SOURCE_INVALID");
	}

	public String normalizedSignature() {
		return field(ownerKind.name()) + field(sourceOccurrence.normalizedSignature())
			+ field(sourceValueVersion.normalizedSignature())
			+ field(consumerOccurrence.normalizedSignature()) + field(Integer.toString(inputPosition))
			+ field(Integer.toString(sourceOrdinal)) + field(supplyKind.name())
			+ field(relocationAction == null ? "-" : relocationAction.normalizedSignature())
			+ field(localMaterializationAction == null ? "-"
				: localMaterializationAction.normalizedSignature());
	}

	@Override
	public int compareTo(InputBindingReceipt that) {
		return normalizedSignature().compareTo(that.normalizedSignature());
	}

	/** Validates graph ownership, selected source semantics, action authority, and use uniqueness. */
	public static List<InputBindingReceipt> validateAndCanonicalize(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementEmissionState> selectedEmissionStates,
		List<RelocationActionKey> selectedRelocations,
		List<LocalMaterializationActionKey> selectedLocalMaterializations,
		List<InputBindingReceipt> receipts) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(selectedEmissionStates, "selectedEmissionStates");
		List<RelocationActionKey> relocations = List.copyOf(Objects.requireNonNull(
			selectedRelocations, "selectedRelocations"));
		List<LocalMaterializationActionKey> locals = List.copyOf(Objects.requireNonNull(
			selectedLocalMaterializations, "selectedLocalMaterializations"));
		List<InputBindingReceipt> canonical = new ArrayList<>(Objects.requireNonNull(receipts, "receipts"));
		canonical.sort(Comparator.naturalOrder());
		List<UseIdentity> uses = new ArrayList<>();
		for(InputBindingReceipt receipt : canonical) {
			Objects.requireNonNull(receipt, "input binding receipt");
			UseIdentity use = new UseIdentity(receipt.ownerKind(), receipt.sourceOccurrence(),
				receipt.consumerOccurrence(), receipt.inputPosition(), receipt.sourceOrdinal());
			if(uses.stream().anyMatch(existing -> existing.sameIdentity(use)))
				throw new IllegalArgumentException("INPUT_BINDING_USE_DUPLICATE");
			uses.add(use);
			validateOne(analysis, selectedEmissionStates, relocations, locals, receipt);
		}
		return List.copyOf(canonical);
	}

	private static void validateOne(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementEmissionState> selected,
		List<RelocationActionKey> selectedRelocations,
		List<LocalMaterializationActionKey> selectedLocals, InputBindingReceipt receipt) {
		NeutralPlacementGraph.Node source = analysis.graph().node(receipt.sourceOccurrence()).orElse(null);
		NeutralPlacementGraph.Node consumer = analysis.graph().node(receipt.consumerOccurrence()).orElse(null);
		if(source == null || source.key() != receipt.sourceOccurrence()
			|| consumer == null || consumer.key() != receipt.consumerOccurrence())
			throw new IllegalArgumentException("INPUT_BINDING_GRAPH_IDENTITY_FOREIGN");
		if(!source.valueVersion().equals(receipt.sourceValueVersion()))
			throw new IllegalArgumentException("INPUT_BINDING_SOURCE_VALUE_VERSION_INVALID");
		validateUseIdentity(analysis, receipt);
		PlacementEmissionState sourceEmission = exactEmission(selected, receipt.sourceOccurrence());
		PlacementEmissionState consumerEmission = exactEmission(selected, receipt.consumerOccurrence());
		if(sourceEmission == null || consumerEmission == null)
			throw new IllegalArgumentException("INPUT_BINDING_ENDPOINT_SELECTION_MISSING");
		if(receipt.supplyKind() == SupplyKind.DIRECT_FOUT
			&& sourceEmission.placementState().output() != FederatedOutput.FOUT)
			throw new IllegalArgumentException("INPUT_BINDING_DIRECT_SOURCE_NOT_FOUT");
		boolean requiresLocalCopy = sourceEmission.placementState().execType() == ExecType.FED
			&& sourceEmission.placementState().output() == FederatedOutput.FOUT
			&& sourceEmission.placementState().fType() != null && !sourceEmission.derivedFedFout();
		boolean localSupply = receipt.supplyKind() == SupplyKind.LOCAL_COPY
			|| receipt.supplyKind() == SupplyKind.NATIVE_LOUT;
		if(localSupply && (receipt.supplyKind() == SupplyKind.LOCAL_COPY) != requiresLocalCopy)
			throw new IllegalArgumentException("INPUT_BINDING_LOCAL_SUPPLY_SELECTION_INVALID");
		if(receipt.supplyKind() == SupplyKind.LOCAL_COPY) {
			if(!containsExact(selectedLocals, receipt.localMaterializationAction()))
				throw new IllegalArgumentException("INPUT_BINDING_LOCAL_ACTION_NOT_SELECTED");
			if(!hasLocalObligation(analysis, receipt))
				throw new IllegalArgumentException("INPUT_BINDING_LOCAL_OBLIGATION_INVALID");
		}
		if(receipt.relocationAction() != null
			&& !graphRelocationSupportsUse(analysis, receipt, consumerEmission.placementState()))
			throw new IllegalArgumentException("INPUT_BINDING_RELOCATION_ACTION_FOREIGN");
		if(receipt.supplyKind() == SupplyKind.RELOCATION
			&& !containsExact(selectedRelocations, receipt.relocationAction()))
			throw new IllegalArgumentException("INPUT_BINDING_RELOCATION_ACTION_NOT_SELECTED");
	}

	private static void validateUseIdentity(PlacementAnalysis analysis, InputBindingReceipt receipt) {
		long matches = switch(receipt.ownerKind()) {
			case COMPILED -> analysis.compiledInputEdgesInCanonicalOrder().stream().filter(edge ->
				edge.producer() == receipt.sourceOccurrence()
					&& edge.consumer() == receipt.consumerOccurrence()
					&& edge.inputPosition() == receipt.inputPosition())
				.filter(edge -> modeledCompiledUse(analysis, edge)).count();
			case LOGICAL_TRANSIENT -> analysis.logicalTransientInputsInCanonicalOrder().stream().filter(input ->
				input.sourceWrite() == receipt.sourceOccurrence()
					&& input.targetRead() == receipt.consumerOccurrence()
					&& input.logicalPosition() == receipt.inputPosition()).count();
			case LOGICAL_FUNCTION -> analysis.logicalFunctionInputsInCanonicalOrder().stream().filter(input ->
				input.sourceArgument() == receipt.sourceOccurrence()
					&& input.targetRead() == receipt.consumerOccurrence()
					&& input.logicalPosition() == receipt.inputPosition()).count();
		};
		if(receipt.sourceOrdinal() >= matches)
			throw new IllegalArgumentException("INPUT_BINDING_USE_IDENTITY_FOREIGN");
	}

	private static boolean hasLocalObligation(PlacementAnalysis analysis, InputBindingReceipt receipt) {
		CompiledHopKey consumer = receipt.consumerOccurrence();
		int position = receipt.inputPosition();
		if(receipt.ownerKind() == OwnerKind.LOGICAL_FUNCTION) {
			var fact = analysis.logicalFunctionInputsInCanonicalOrder().stream().filter(input ->
				input.sourceArgument() == receipt.sourceOccurrence()
					&& input.targetRead() == receipt.consumerOccurrence()
					&& input.logicalPosition() == receipt.inputPosition())
				.skip(receipt.sourceOrdinal()).findFirst().orElseThrow();
			consumer = analysis.requireExactPhysicalFunctionInputConsumer(fact);
			position = fact.callInputPosition();
		}
		CompiledHopKey expectedConsumer = consumer;
		int expectedPosition = position;
		return receipt.localMaterializationAction().obligations().stream().anyMatch(obligation ->
			obligation.consumerOccurrence() == expectedConsumer
				&& obligation.inputPosition() == expectedPosition);
	}

	private static boolean graphRelocationSupportsUse(PlacementAnalysis analysis,
		InputBindingReceipt receipt, PlacementState consumerPlacement) {
		return analysis.graph().relocationActions().stream().anyMatch(action ->
			action.key() == receipt.relocationAction()
				&& action.key().sourceValueVersion().equals(receipt.sourceValueVersion())
				&& action.obligations().stream().anyMatch(obligation ->
					obligation.consumer() == receipt.consumerOccurrence()
						&& obligation.inputPosition() == receipt.inputPosition()
						&& obligation.requiredPlacement().equals(consumerPlacement)));
	}

	private static boolean modeledCompiledUse(PlacementAnalysis analysis,
		PlacementAnalysis.CompiledInputEdgeFact edge) {
		if(analysis.isCoordinatorMetadataOnlyInput(edge)
			|| PlacementCostSemantics.isLatentWdivmmTransposePairBoundary(analysis,
				edge.producer(), edge.consumer(), edge.inputPosition()))
			return false;
		return !(analysis.graph().node(edge.consumer()).map(node -> node.kind()
			== NeutralPlacementGraph.NodeKind.FUNCTION_CALL).orElse(false)
			&& analysis.isDmlFunctionCallBoundary(edge.consumer()));
	}

	private static <T> boolean containsExact(List<T> values, T expected) {
		return values.stream().anyMatch(value -> value == expected || value.equals(expected));
	}

	private static PlacementEmissionState exactEmission(
		Map<CompiledHopKey,PlacementEmissionState> selected, CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey,PlacementEmissionState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return entry.getValue();
		return null;
	}

	private record UseIdentity(OwnerKind ownerKind, CompiledHopKey source,
		CompiledHopKey consumer, int inputPosition, int sourceOrdinal) {
		boolean sameIdentity(UseIdentity that) {
			return ownerKind == that.ownerKind && source == that.source && consumer == that.consumer
				&& inputPosition == that.inputPosition && sourceOrdinal == that.sourceOrdinal;
		}
	}

	private static String field(String value) {
		return value.length() + ":" + value;
	}
}

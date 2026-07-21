/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Immutable shadow-mode result of exact MinST source-partition enumeration. */
public final class MinStExactSelection {
	public static final String UNIQUE = "UNIQUE";
	public static final String TIE_UNSPECIFIED = "TIE_UNSPECIFIED";

	private final long objectiveBits;
	private final List<Long> sourcePartitionNodeIds;
	private final List<PlacementState> selectedStatesInScopeOrder;
	private final List<ObligationReceipt> obligationReceiptsInOrder;
	private final String tieCertificate;
	private final List<List<Long>> minimumSourcePartitionCertificates;

	MinStExactSelection(long objectiveBits, List<Long> sourcePartitionNodeIds,
		List<PlacementState> selectedStatesInScopeOrder,
		List<ObligationReceipt> obligationReceiptsInOrder, String tieCertificate,
		List<List<Long>> minimumSourcePartitionCertificates) {
		this.objectiveBits = objectiveBits;
		this.sourcePartitionNodeIds = List.copyOf(sourcePartitionNodeIds);
		this.selectedStatesInScopeOrder = List.copyOf(selectedStatesInScopeOrder);
		this.obligationReceiptsInOrder = List.copyOf(obligationReceiptsInOrder);
		this.tieCertificate = Objects.requireNonNull(tieCertificate, "tieCertificate");
		this.minimumSourcePartitionCertificates = minimumSourcePartitionCertificates.stream()
			.map(List::copyOf).toList();
	}

	public long objectiveBits() { return objectiveBits; }
	public List<Long> sourcePartitionNodeIds() { return sourcePartitionNodeIds; }
	public List<PlacementState> selectedStatesInScopeOrder() { return selectedStatesInScopeOrder; }
	public List<ObligationReceipt> obligationReceiptsInOrder() { return obligationReceiptsInOrder; }
	public String tieCertificate() { return tieCertificate; }
	public List<List<Long>> minimumSourcePartitionCertificates() { return minimumSourcePartitionCertificates; }
	public List<List<Long>> minimaCertificates() { return minimumSourcePartitionCertificates; }

	/** Immutable authority receipt for a selected upload/download obligation endpoint. */
	public static final class ObligationReceipt {
		private final Direction direction;
		private final CompiledHopKey producerKey;
		private final CompiledHopKey consumerKey;
		private final int inputPosition;
		private final PlacementState requiredPlacement;
		private final String actionSignature;

		ObligationReceipt(Direction direction, CompiledHopKey producerKey, CompiledHopKey consumerKey,
			int inputPosition, PlacementState requiredPlacement, String actionSignature) {
			this.direction = Objects.requireNonNull(direction, "direction");
			this.producerKey = Objects.requireNonNull(producerKey, "producerKey");
			this.consumerKey = Objects.requireNonNull(consumerKey, "consumerKey");
			this.inputPosition = inputPosition;
			this.requiredPlacement = Objects.requireNonNull(requiredPlacement, "requiredPlacement");
			this.actionSignature = Objects.requireNonNull(actionSignature, "actionSignature");
		}

		public Direction direction() { return direction; }
		public Direction kind() { return direction; }
		public CompiledHopKey producerKey() { return producerKey; }
		public CompiledHopKey producer() { return producerKey; }
		public CompiledHopKey consumerKey() { return consumerKey; }
		public CompiledHopKey consumer() { return consumerKey; }
		public int inputPosition() { return inputPosition; }
		public PlacementState requiredPlacement() { return requiredPlacement; }
		public PlacementState targetPlacement() { return requiredPlacement; }
		public String actionSignature() { return actionSignature; }
	}
}

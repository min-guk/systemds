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

package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;

import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.BatchTag;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.Control;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ControlOp;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ErrorCode;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PhaseIdentity;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PhaseKind;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.PrivacyLabel;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.Reply;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.ReplyStatus;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.SourceDataType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.SourceResidencyReceipt;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseWire.StreamFence;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.junit.Test;

public class FederatedPhaseWireTest {
	private final UUID _attempt = UUID.randomUUID();
	private final UUID _coordinator = UUID.randomUUID();
	private final UUID _worker = UUID.randomUUID();
	private final UUID _stream = UUID.randomUUID();

	@Test
	public void phaseControlRequestRoundTripCarriesOnlyControlPayload() throws Exception {
		PhaseIdentity identity = identity(PhaseKind.PLANNING, 1);
		Control control = new Control(identity, null, 0, ControlOp.BEGIN_PHASE, 1000,
			new StreamFence[0], new String[0], null);
		FederatedRequest request = new FederatedRequest(RequestType.PHASE_CONTROL, 99, control);
		request.setTID(7);

		FederatedRequest clone = request.deepClone();
		assertEquals(RequestType.PHASE_CONTROL, clone.getType());
		assertNull(clone.getPhaseBatchTag());
		assertSame(control, clone.getParam(0));

		FederatedRequest restored = roundTrip(request);
		assertEquals(RequestType.PHASE_CONTROL, restored.getType());
		assertNull(restored.getPhaseBatchTag());
		Control restoredControl = (Control) restored.getParam(0);
		assertEquals(ControlOp.BEGIN_PHASE, restoredControl.getOp());
		assertEquals(identity, restoredControl.getIdentity());
	}

	@Test
	public void ordinaryRequestCloneAndRoundTripPreserveImmutableTag() throws Exception {
		BatchTag tag = new BatchTag(identity(PhaseKind.PLANNING, 1), _worker, _stream, 7, 11);
		FederatedRequest request = new FederatedRequest(RequestType.NOOP, 99);
		request.setPhaseBatchTag(tag);
		assertSame(tag, request.deepClone().getPhaseBatchTag());
		assertEquals(tag, roundTrip(request).getPhaseBatchTag());
	}

	@Test
	public void legacyRequestAndResponseRemainUntaggedAcrossCloneAndSerialization() throws Exception {
		FederatedRequest request = new FederatedRequest(RequestType.NOOP, 12);
		request.setTID(4);
		assertNull(request.getPhaseBatchTag());
		assertNull(request.deepClone().getPhaseBatchTag());
		FederatedRequest restored = roundTrip(request);
		assertEquals(RequestType.NOOP, restored.getType());
		assertEquals(4, restored.getTID());
		assertNull(restored.getPhaseBatchTag());

		FederatedResponse response = roundTrip(new FederatedResponse(ResponseType.SUCCESS_EMPTY));
		assertTrue(response.isSuccessful());
		assertNull(response.getPhaseBatchTag());
	}

	@Test
	public void responseCanEchoOptionalBatchTag() throws Exception {
		BatchTag tag = new BatchTag(identity(PhaseKind.TIMED, 4), _worker, _stream, 3, 8);
		FederatedResponse response = new FederatedResponse(ResponseType.SUCCESS_EMPTY);
		response.setPhaseBatchTag(tag);
		FederatedResponse restored = roundTrip(response);
		assertEquals(tag, restored.getPhaseBatchTag());
	}

	@Test
	public void controlAndReplyArraysAreDefensivelyCopied() {
		PhaseIdentity preread = identity(PhaseKind.PREREAD, 3);
		String[] sourceIds = {"source-X"};
		Control control = new Control(preread, _worker, 4, ControlOp.PREREAD_SOURCES, 1000,
			new StreamFence[0], sourceIds, null);
		sourceIds[0] = "mutated";
		assertArrayEquals(new String[] {"source-X"}, control.getSourceIds());
		String[] returnedSources = control.getSourceIds();
		returnedSources[0] = "also-mutated";
		assertArrayEquals(new String[] {"source-X"}, control.getSourceIds());

		StreamFence fence = new StreamFence(_stream, 3, 8);
		StreamFence[] fences = {fence};
		SourceResidencyReceipt source = new SourceResidencyReceipt("source-X", PrivacyLabel.PRIVATE_AGGREGATE,
			SourceDataType.MATRIX, "source-sha", "sidecar-sha", true, true, UUID.randomUUID());
		SourceResidencyReceipt[] sources = {source};
		Reply reply = reply(fences, sources);
		fences[0] = new StreamFence(UUID.randomUUID(), 9, 9);
		sources[0] = null;
		assertSame(fence, reply.getCompletedFences()[0]);
		assertSame(source, reply.getSources()[0]);
		assertNotSame(reply.getCompletedFences(), reply.getCompletedFences());
		assertNotSame(reply.getSources(), reply.getSources());
	}

	@Test
	public void controlRejectsMixedOrUnboundedPayloads() {
		PhaseIdentity planning = identity(PhaseKind.PLANNING, 1);
		new Control(planning, null, 0, ControlOp.BEGIN_PHASE, 1000,
			new StreamFence[0], new String[0], null);
		assertThrows(IllegalArgumentException.class, () -> new Control(identity(PhaseKind.PLANNING, 2), null, 0,
			ControlOp.BEGIN_PHASE, 1000, new StreamFence[0], new String[0], null));
		assertThrows(IllegalArgumentException.class, () -> new Control(identity(PhaseKind.WARMUP, 2), null, 0,
			ControlOp.BEGIN_PHASE, 1000, new StreamFence[0], new String[0], null));
		assertThrows(IllegalArgumentException.class, () -> new Control(planning, null, 0,
			ControlOp.END_PHASE, 1000, new StreamFence[0], new String[0], null));
		assertThrows(IllegalArgumentException.class, () -> new Control(planning, _worker, 0,
			ControlOp.BEGIN_PHASE, 1000, new StreamFence[0], new String[] {"unexpected"}, null));
		assertThrows(IllegalArgumentException.class, () -> new Control(planning, _worker, 0,
			ControlOp.BEGIN_PHASE, FederatedPhaseWire.MAX_TIMEOUT_MILLIS + 1,
			new StreamFence[0], new String[0], null));
		assertThrows(IllegalArgumentException.class, () -> new Control(identity(PhaseKind.TIMED, 4), _worker, 0,
			ControlOp.BEGIN_PHASE, 1000, new StreamFence[0], new String[0], null));
		assertThrows(IllegalArgumentException.class, () -> new Control(identity(PhaseKind.PREREAD, 3), _worker, 0,
			ControlOp.PREREAD_SOURCES, 1000, new StreamFence[0], new String[] {"x", "x"}, null));
	}

	@Test
	public void replyUsesFixedErrorAndCounterContract() throws Exception {
		Reply reply = reply(new StreamFence[] {new StreamFence(_stream, 3, 8)},
			new SourceResidencyReceipt[] {new SourceResidencyReceipt("source-X", PrivacyLabel.PRIVATE_AGGREGATE,
				SourceDataType.MATRIX, "source-sha", "sidecar-sha", true, true, UUID.randomUUID())});
		Reply restored = roundTrip(reply);
		assertEquals(FederatedPhaseWire.PROTOCOL_VERSION, restored.getProtocolVersion());
		assertEquals(ReplyStatus.ACK, restored.getStatus());
		assertEquals(ErrorCode.NONE, restored.getError());
		assertTrue(restored.isTerminal());
		assertFalse(restored.isResetVerified());
		assertThrows(IllegalArgumentException.class, () -> new Reply(_attempt, 3, 5, ControlOp.END_PHASE,
			ReplyStatus.ACK, ErrorCode.TASK_FAILURE, _worker, 222, "stage", "settings", new StreamFence[0],
			1, 0, 1, 0, 0, 0, true, true, false, null, new SourceResidencyReceipt[0]));
	}

	@Test
	public void replyEnforcesExactOverflowSafeTaskAccounting() {
		Reply nonterminalBegin = replyWithCounters(ControlOp.BEGIN_PHASE, ReplyStatus.ACK, ErrorCode.NONE,
			1, 0, 0, 1, 0, 0, false, false);
		assertFalse(nonterminalBegin.isTerminal());
		assertEquals(1, nonterminalBegin.getOutstanding());

		assertThrows(IllegalArgumentException.class, () -> replyWithCounters(ControlOp.END_PHASE,
			ReplyStatus.ACK, ErrorCode.NONE, 1, 1, 1, 0, 0, 0, true, false));
		assertThrows(IllegalArgumentException.class, () -> replyWithCounters(ControlOp.END_PHASE,
			ReplyStatus.ACK, ErrorCode.NONE, 1, 0, 1, 0, 0, 0, false, true));
		assertThrows(IllegalArgumentException.class, () -> replyWithCounters(ControlOp.END_PHASE,
			ReplyStatus.FAILED, ErrorCode.TASK_FAILURE, 1, 0, 1, 0, 0, 2, true, true));
		assertThrows(IllegalArgumentException.class, () -> replyWithCounters(ControlOp.END_PHASE,
			ReplyStatus.ACK, ErrorCode.NONE, 1, 0, 1, 0, 1, 0, true, true));
		assertThrows(IllegalArgumentException.class, () -> replyWithCounters(ControlOp.END_PHASE,
			ReplyStatus.FAILED, ErrorCode.TASK_FAILURE, Long.MAX_VALUE, 1, Long.MAX_VALUE, 1,
			0, 0, true, false));
		assertThrows(IllegalArgumentException.class, () -> replyWithCounters(ControlOp.END_PHASE,
			ReplyStatus.FAILED, ErrorCode.TASK_FAILURE, Long.MAX_VALUE, 0, Long.MAX_VALUE, 1,
			0, 0, true, false));
	}

	@Test
	public void deserializationBreaksControlAndReplyArrayAliases() throws Exception {
		Control control = new Control(identity(PhaseKind.PREREAD, 3), _worker, 4,
			ControlOp.PREREAD_SOURCES, 1000, new StreamFence[0], new String[] {"source-X"}, null);
		String[] internalSourceIds = (String[]) fieldValue(control, "_sourceIds");
		Object[] restoredControlGraph = roundTrip(new Object[] {control, internalSourceIds});
		Control restoredControl = (Control) restoredControlGraph[0];
		((String[]) restoredControlGraph[1])[0] = "aliased-mutation";
		assertArrayEquals(new String[] {"source-X"}, restoredControl.getSourceIds());

		StreamFence fence = new StreamFence(_stream, 3, 8);
		SourceResidencyReceipt source = new SourceResidencyReceipt("source-X", PrivacyLabel.PRIVATE_AGGREGATE,
			SourceDataType.MATRIX, "source-sha", "sidecar-sha", true, true, UUID.randomUUID());
		Reply reply = reply(new StreamFence[] {fence}, new SourceResidencyReceipt[] {source});
		StreamFence[] internalFences = (StreamFence[]) fieldValue(reply, "_completedFences");
		SourceResidencyReceipt[] internalSources = (SourceResidencyReceipt[]) fieldValue(reply, "_sources");
		Object[] restoredReplyGraph = roundTrip(new Object[] {reply, internalFences, internalSources});
		Reply restoredReply = (Reply) restoredReplyGraph[0];
		((StreamFence[]) restoredReplyGraph[1])[0] = new StreamFence(UUID.randomUUID(), 9, 9);
		((SourceResidencyReceipt[]) restoredReplyGraph[2])[0] = null;
		assertEquals(fence, restoredReply.getCompletedFences()[0]);
		assertEquals(source.getSourceId(), restoredReply.getSources()[0].getSourceId());
	}

	@Test
	public void deserializationRevalidatesVersion() throws Exception {
		PhaseIdentity identity = identity(PhaseKind.PLANNING, 1);
		Field version = PhaseIdentity.class.getDeclaredField("_protocolVersion");
		version.setAccessible(true);
		version.setInt(identity, FederatedPhaseWire.PROTOCOL_VERSION + 1);
		byte[] serialized = serialize(identity);
		assertThrows(InvalidObjectException.class, () -> deserialize(serialized));
	}

	private PhaseIdentity identity(PhaseKind kind, long epoch) {
		return new PhaseIdentity(_attempt, "condition", "stage", "manifest", "settings",
			_coordinator, 111, epoch, kind);
	}

	private Reply reply(StreamFence[] fences, SourceResidencyReceipt[] sources) {
		return new Reply(_attempt, 3, 5, ControlOp.END_PHASE, ReplyStatus.ACK, ErrorCode.NONE,
			_worker, 222, "stage", "settings", fences, 1, 1, 2, 0, 0, 0,
			true, true, false, UUID.randomUUID(), sources);
	}

	private Reply replyWithCounters(ControlOp op, ReplyStatus status, ErrorCode error,
		long roots, long children, long completed, long outstanding, long rejected, long failed,
		boolean rootsClosed, boolean terminal) {
		return new Reply(_attempt, 3, 5, op, status, error, _worker, 222, "stage", "settings",
			new StreamFence[0], roots, children, completed, outstanding, rejected, failed,
			rootsClosed, terminal, false, null, new SourceResidencyReceipt[0]);
	}

	private static Object fieldValue(Object value, String name) throws Exception {
		Field field = value.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T roundTrip(T value) throws Exception {
		return (T) deserialize(serialize(value));
	}

	private static byte[] serialize(Object value) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try(ObjectOutputStream output = new ObjectOutputStream(buffer)) {
			output.writeObject(value);
		}
		return buffer.toByteArray();
	}

	private static Object deserialize(byte[] value) throws Exception {
		try(ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(value))) {
			return input.readObject();
		}
	}
}

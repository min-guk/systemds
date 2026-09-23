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

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, versioned wire values for native federated phase control.
 *
 * This class defines identity and bounded control payloads only. Worker handling is deliberately not installed here;
 * consequently {@link FederatedRequest.RequestType#PHASE_CONTROL} remains fail-closed until a handler implements it.
 */
public final class FederatedPhaseWire {
	public static final int PROTOCOL_VERSION = 1;
	public static final long INITIAL_EPOCH = 1;
	public static final int MAX_STRING_LENGTH = 512;
	public static final int MAX_ARRAY_LENGTH = 1024;
	public static final long MAX_TIMEOUT_MILLIS = 600_000;

	private FederatedPhaseWire() {
		// utility class
	}

	public enum PhaseKind {
		PLANNING, WARMUP, PREREAD, TIMED
	}

	public enum ControlOp {
		BEGIN_PHASE, END_PHASE, RESET_WARM_STATE, PREREAD_SOURCES, ABORT_SESSION, CLOSE_SESSION
	}

	public enum ReplyStatus {
		ACK, FAILED, QUARANTINED
	}

	public enum ErrorCode {
		NONE,
		UNSUPPORTED_VERSION,
		INVALID_IDENTITY,
		WORKER_IDENTITY_MISMATCH,
		STALE_EPOCH,
		INVALID_SEQUENCE,
		INVALID_CONTROL,
		INVALID_BATCH,
		TIMEOUT,
		TASK_FAILURE,
		PRIVACY_VIOLATION,
		SOURCE_NOT_FOUND,
		SOURCE_IDENTITY_MISMATCH,
		RESIDENCY_FAILURE,
		SESSION_QUARANTINED,
		INTERNAL_ERROR
	}

	public enum PrivacyLabel {
		PUBLIC, PRIVATE, PRIVATE_AGGREGATE
	}

	public enum SourceDataType {
		MATRIX, FRAME
	}

	public static final class PhaseIdentity implements Serializable {
		private static final long serialVersionUID = 1L;

		private final int _protocolVersion;
		private final UUID _attemptId;
		private final String _conditionDigest;
		private final String _stageSeal;
		private final String _sourceManifestDigest;
		private final String _settingsDigest;
		private final UUID _coordinatorJvmInstanceId;
		private final long _coordinatorPid;
		private final long _epoch;
		private final PhaseKind _kind;

		public PhaseIdentity(UUID attemptId, String conditionDigest, String stageSeal,
			String sourceManifestDigest, String settingsDigest, UUID coordinatorJvmInstanceId,
			long coordinatorPid, long epoch, PhaseKind kind) {
			this(PROTOCOL_VERSION, attemptId, conditionDigest, stageSeal, sourceManifestDigest,
				settingsDigest, coordinatorJvmInstanceId, coordinatorPid, epoch, kind);
		}

		PhaseIdentity(int protocolVersion, UUID attemptId, String conditionDigest, String stageSeal,
			String sourceManifestDigest, String settingsDigest, UUID coordinatorJvmInstanceId,
			long coordinatorPid, long epoch, PhaseKind kind) {
			_protocolVersion = protocolVersion;
			_attemptId = attemptId;
			_conditionDigest = conditionDigest;
			_stageSeal = stageSeal;
			_sourceManifestDigest = sourceManifestDigest;
			_settingsDigest = settingsDigest;
			_coordinatorJvmInstanceId = coordinatorJvmInstanceId;
			_coordinatorPid = coordinatorPid;
			_epoch = epoch;
			_kind = kind;
			validate();
		}

		public int getProtocolVersion() { return _protocolVersion; }
		public UUID getAttemptId() { return _attemptId; }
		public String getConditionDigest() { return _conditionDigest; }
		public String getStageSeal() { return _stageSeal; }
		public String getSourceManifestDigest() { return _sourceManifestDigest; }
		public String getSettingsDigest() { return _settingsDigest; }
		public UUID getCoordinatorJvmInstanceId() { return _coordinatorJvmInstanceId; }
		public long getCoordinatorPid() { return _coordinatorPid; }
		public long getEpoch() { return _epoch; }
		public PhaseKind getKind() { return _kind; }

		private void validate() {
			requireVersion(_protocolVersion);
			requireNonNull(_attemptId, "attemptId");
			requireString(_conditionDigest, "conditionDigest");
			requireString(_stageSeal, "stageSeal");
			requireString(_sourceManifestDigest, "sourceManifestDigest");
			requireString(_settingsDigest, "settingsDigest");
			requireNonNull(_coordinatorJvmInstanceId, "coordinatorJvmInstanceId");
			requirePositive(_coordinatorPid, "coordinatorPid");
			requireNonNegative(_epoch, "epoch");
			requireNonNull(_kind, "kind");
		}

		private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
			input.defaultReadObject();
			validateAfterDeserialization(this::validate);
		}

		@Override
		public boolean equals(Object object) {
			if(this == object)
				return true;
			if(!(object instanceof PhaseIdentity))
				return false;
			PhaseIdentity that = (PhaseIdentity) object;
			return _protocolVersion == that._protocolVersion && _coordinatorPid == that._coordinatorPid
				&& _epoch == that._epoch && _attemptId.equals(that._attemptId)
				&& _conditionDigest.equals(that._conditionDigest) && _stageSeal.equals(that._stageSeal)
				&& _sourceManifestDigest.equals(that._sourceManifestDigest)
				&& _settingsDigest.equals(that._settingsDigest)
				&& _coordinatorJvmInstanceId.equals(that._coordinatorJvmInstanceId) && _kind == that._kind;
		}

		@Override
		public int hashCode() {
			return Objects.hash(_protocolVersion, _attemptId, _conditionDigest, _stageSeal,
				_sourceManifestDigest, _settingsDigest, _coordinatorJvmInstanceId, _coordinatorPid, _epoch, _kind);
		}
	}

	public static final class BatchTag implements Serializable {
		private static final long serialVersionUID = 1L;

		private final PhaseIdentity _identity;
		private final UUID _expectedWorkerJvmInstanceId;
		private final UUID _streamId;
		private final long _normalizedTid;
		private final long _batchSequence;

		public BatchTag(PhaseIdentity identity, UUID expectedWorkerJvmInstanceId, UUID streamId,
			long normalizedTid, long batchSequence) {
			_identity = identity;
			_expectedWorkerJvmInstanceId = expectedWorkerJvmInstanceId;
			_streamId = streamId;
			_normalizedTid = normalizedTid;
			_batchSequence = batchSequence;
			validate();
		}

		public PhaseIdentity getIdentity() { return _identity; }
		public UUID getExpectedWorkerJvmInstanceId() { return _expectedWorkerJvmInstanceId; }
		public UUID getStreamId() { return _streamId; }
		public long getNormalizedTid() { return _normalizedTid; }
		public long getBatchSequence() { return _batchSequence; }

		private void validate() {
			requireNonNull(_identity, "identity");
			requireNonNull(_expectedWorkerJvmInstanceId, "expectedWorkerJvmInstanceId");
			requireNonNull(_streamId, "streamId");
			requireNonNegative(_normalizedTid, "normalizedTid");
			requireNonNegative(_batchSequence, "batchSequence");
		}

		private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
			input.defaultReadObject();
			validateAfterDeserialization(this::validate);
		}

		@Override
		public boolean equals(Object object) {
			if(this == object)
				return true;
			if(!(object instanceof BatchTag))
				return false;
			BatchTag that = (BatchTag) object;
			return _normalizedTid == that._normalizedTid && _batchSequence == that._batchSequence
				&& _identity.equals(that._identity)
				&& _expectedWorkerJvmInstanceId.equals(that._expectedWorkerJvmInstanceId)
				&& _streamId.equals(that._streamId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(_identity, _expectedWorkerJvmInstanceId, _streamId, _normalizedTid, _batchSequence);
		}
	}

	public static final class StreamFence implements Serializable {
		private static final long serialVersionUID = 1L;

		private final UUID _streamId;
		private final long _normalizedTid;
		private final long _lastSequence;

		public StreamFence(UUID streamId, long normalizedTid, long lastSequence) {
			_streamId = streamId;
			_normalizedTid = normalizedTid;
			_lastSequence = lastSequence;
			validate();
		}

		public UUID getStreamId() { return _streamId; }
		public long getNormalizedTid() { return _normalizedTid; }
		public long getLastSequence() { return _lastSequence; }

		private void validate() {
			requireNonNull(_streamId, "streamId");
			requireNonNegative(_normalizedTid, "normalizedTid");
			requireNonNegative(_lastSequence, "lastSequence");
		}

		private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
			input.defaultReadObject();
			validateAfterDeserialization(this::validate);
		}

		@Override
		public boolean equals(Object object) {
			if(this == object)
				return true;
			if(!(object instanceof StreamFence))
				return false;
			StreamFence that = (StreamFence) object;
			return _normalizedTid == that._normalizedTid && _lastSequence == that._lastSequence
				&& _streamId.equals(that._streamId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(_streamId, _normalizedTid, _lastSequence);
		}
	}

	public static final class Control implements Serializable {
		private static final long serialVersionUID = 1L;

		private final PhaseIdentity _identity;
		private final UUID _expectedWorkerJvmInstanceId;
		private final long _controlSequence;
		private final ControlOp _op;
		private final long _timeoutMillis;
		private final StreamFence[] _fences;
		private final String[] _sourceIds;
		private final UUID _residencyLeaseId;

		public Control(PhaseIdentity identity, UUID expectedWorkerJvmInstanceId, long controlSequence,
			ControlOp op, long timeoutMillis, StreamFence[] fences, String[] sourceIds, UUID residencyLeaseId) {
			_identity = identity;
			_expectedWorkerJvmInstanceId = expectedWorkerJvmInstanceId;
			_controlSequence = controlSequence;
			_op = op;
			_timeoutMillis = timeoutMillis;
			_fences = copy(fences);
			_sourceIds = copy(sourceIds);
			_residencyLeaseId = residencyLeaseId;
			validate();
		}

		public PhaseIdentity getIdentity() { return _identity; }
		public UUID getExpectedWorkerJvmInstanceId() { return _expectedWorkerJvmInstanceId; }
		public long getControlSequence() { return _controlSequence; }
		public ControlOp getOp() { return _op; }
		public long getTimeoutMillis() { return _timeoutMillis; }
		public StreamFence[] getFences() { return copy(_fences); }
		public String[] getSourceIds() { return copy(_sourceIds); }
		public UUID getResidencyLeaseId() { return _residencyLeaseId; }

		private void validate() {
			requireNonNull(_identity, "identity");
			requireNonNegative(_controlSequence, "controlSequence");
			requireNonNull(_op, "op");
			if(_timeoutMillis <= 0 || _timeoutMillis > MAX_TIMEOUT_MILLIS)
				throw new IllegalArgumentException("timeoutMillis is outside worker policy bounds");
			requireArray(_fences, "fences");
			requireArray(_sourceIds, "sourceIds");
			validateFences(_fences);
			validateSourceIds(_sourceIds);
			if(_expectedWorkerJvmInstanceId == null && (_op != ControlOp.BEGIN_PHASE
				|| _identity.getKind() != PhaseKind.PLANNING || _identity.getEpoch() != INITIAL_EPOCH))
				throw new IllegalArgumentException("null expectedWorkerJvmInstanceId requires initial PLANNING epoch");
			switch(_op) {
				case BEGIN_PHASE:
					requireEmpty(_fences, "BEGIN_PHASE fences");
					requireEmpty(_sourceIds, "BEGIN_PHASE sourceIds");
					if((_identity.getKind() == PhaseKind.TIMED) != (_residencyLeaseId != null))
						throw new IllegalArgumentException("TIMED BEGIN_PHASE requires exactly one residency lease");
					break;
				case END_PHASE:
					requireWorkerIdentity();
					requireEmpty(_sourceIds, "END_PHASE sourceIds");
					requireNull(_residencyLeaseId, "END_PHASE residencyLeaseId");
					break;
				case RESET_WARM_STATE:
					requireWorkerIdentity();
					requireKind(PhaseKind.WARMUP);
					requireEmptyPayload();
					break;
				case PREREAD_SOURCES:
					requireWorkerIdentity();
					requireKind(PhaseKind.PREREAD);
					requireEmpty(_fences, "PREREAD_SOURCES fences");
					if(_sourceIds.length == 0)
						throw new IllegalArgumentException("PREREAD_SOURCES requires sourceIds");
					requireNull(_residencyLeaseId, "PREREAD_SOURCES residencyLeaseId");
					break;
				case ABORT_SESSION:
				case CLOSE_SESSION:
					requireWorkerIdentity();
					requireEmptyPayload();
					break;
				default:
					throw new IllegalArgumentException("unsupported control operation");
			}
		}

		private void requireWorkerIdentity() {
			if(_expectedWorkerJvmInstanceId == null)
				throw new IllegalArgumentException("control requires expectedWorkerJvmInstanceId");
		}

		private void requireKind(PhaseKind expected) {
			if(_identity.getKind() != expected)
				throw new IllegalArgumentException(_op + " requires " + expected + " identity");
		}

		private void requireEmptyPayload() {
			requireEmpty(_fences, _op + " fences");
			requireEmpty(_sourceIds, _op + " sourceIds");
			requireNull(_residencyLeaseId, _op + " residencyLeaseId");
		}

		private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
			input.defaultReadObject();
			validateAfterDeserialization(this::validate);
		}

		private Object readResolve() throws ObjectStreamException {
			return new Control(_identity, _expectedWorkerJvmInstanceId, _controlSequence, _op,
				_timeoutMillis, _fences, _sourceIds, _residencyLeaseId);
		}
	}

	public static final class SourceResidencyReceipt implements Serializable {
		private static final long serialVersionUID = 1L;

		private final String _sourceId;
		private final PrivacyLabel _privacyLabel;
		private final SourceDataType _dataType;
		private final String _sourceIdentity;
		private final String _sidecarIdentity;
		private final boolean _readComplete;
		private final boolean _residentAtFence;
		private final UUID _objectGenerationId;

		public SourceResidencyReceipt(String sourceId, PrivacyLabel privacyLabel, SourceDataType dataType,
			String sourceIdentity, String sidecarIdentity, boolean readComplete, boolean residentAtFence,
			UUID objectGenerationId) {
			_sourceId = sourceId;
			_privacyLabel = privacyLabel;
			_dataType = dataType;
			_sourceIdentity = sourceIdentity;
			_sidecarIdentity = sidecarIdentity;
			_readComplete = readComplete;
			_residentAtFence = residentAtFence;
			_objectGenerationId = objectGenerationId;
			validate();
		}

		public String getSourceId() { return _sourceId; }
		public PrivacyLabel getPrivacyLabel() { return _privacyLabel; }
		public SourceDataType getDataType() { return _dataType; }
		public String getSourceIdentity() { return _sourceIdentity; }
		public String getSidecarIdentity() { return _sidecarIdentity; }
		public boolean isReadComplete() { return _readComplete; }
		public boolean isResidentAtFence() { return _residentAtFence; }
		public UUID getObjectGenerationId() { return _objectGenerationId; }

		private void validate() {
			requireString(_sourceId, "sourceId");
			requireNonNull(_privacyLabel, "privacyLabel");
			requireNonNull(_dataType, "dataType");
			requireString(_sourceIdentity, "sourceIdentity");
			requireString(_sidecarIdentity, "sidecarIdentity");
			requireNonNull(_objectGenerationId, "objectGenerationId");
			if(_residentAtFence && !_readComplete)
				throw new IllegalArgumentException("resident source must have completed its read");
		}

		private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
			input.defaultReadObject();
			validateAfterDeserialization(this::validate);
		}
	}

	public static final class Reply implements Serializable {
		private static final long serialVersionUID = 1L;

		private final int _protocolVersion;
		private final UUID _attemptId;
		private final long _epoch;
		private final long _controlSequence;
		private final ControlOp _op;
		private final ReplyStatus _status;
		private final ErrorCode _error;
		private final UUID _workerJvmInstanceId;
		private final long _workerPid;
		private final String _acceptedStageSeal;
		private final String _acceptedSettingsDigest;
		private final StreamFence[] _completedFences;
		private final long _rootsRegistered;
		private final long _childrenRegistered;
		private final long _tasksCompleted;
		private final long _outstanding;
		private final long _rejectedTasks;
		private final long _failedTasks;
		private final boolean _rootsClosed;
		private final boolean _terminal;
		private final boolean _resetVerified;
		private final UUID _residencyLeaseId;
		private final SourceResidencyReceipt[] _sources;

		public Reply(UUID attemptId, long epoch, long controlSequence, ControlOp op, ReplyStatus status,
			ErrorCode error, UUID workerJvmInstanceId, long workerPid, String acceptedStageSeal,
			String acceptedSettingsDigest, StreamFence[] completedFences, long rootsRegistered,
			long childrenRegistered, long tasksCompleted, long outstanding, long rejectedTasks,
			long failedTasks, boolean rootsClosed, boolean terminal, boolean resetVerified,
			UUID residencyLeaseId, SourceResidencyReceipt[] sources) {
			this(PROTOCOL_VERSION, attemptId, epoch, controlSequence, op, status, error,
				workerJvmInstanceId, workerPid, acceptedStageSeal, acceptedSettingsDigest, completedFences,
				rootsRegistered, childrenRegistered, tasksCompleted, outstanding, rejectedTasks, failedTasks,
				rootsClosed, terminal, resetVerified, residencyLeaseId, sources);
		}

		Reply(int protocolVersion, UUID attemptId, long epoch, long controlSequence, ControlOp op,
			ReplyStatus status, ErrorCode error, UUID workerJvmInstanceId, long workerPid,
			String acceptedStageSeal, String acceptedSettingsDigest, StreamFence[] completedFences,
			long rootsRegistered, long childrenRegistered, long tasksCompleted, long outstanding,
			long rejectedTasks, long failedTasks, boolean rootsClosed, boolean terminal,
			boolean resetVerified, UUID residencyLeaseId, SourceResidencyReceipt[] sources) {
			_protocolVersion = protocolVersion;
			_attemptId = attemptId;
			_epoch = epoch;
			_controlSequence = controlSequence;
			_op = op;
			_status = status;
			_error = error;
			_workerJvmInstanceId = workerJvmInstanceId;
			_workerPid = workerPid;
			_acceptedStageSeal = acceptedStageSeal;
			_acceptedSettingsDigest = acceptedSettingsDigest;
			_completedFences = copy(completedFences);
			_rootsRegistered = rootsRegistered;
			_childrenRegistered = childrenRegistered;
			_tasksCompleted = tasksCompleted;
			_outstanding = outstanding;
			_rejectedTasks = rejectedTasks;
			_failedTasks = failedTasks;
			_rootsClosed = rootsClosed;
			_terminal = terminal;
			_resetVerified = resetVerified;
			_residencyLeaseId = residencyLeaseId;
			_sources = copy(sources);
			validate();
		}

		public int getProtocolVersion() { return _protocolVersion; }
		public UUID getAttemptId() { return _attemptId; }
		public long getEpoch() { return _epoch; }
		public long getControlSequence() { return _controlSequence; }
		public ControlOp getOp() { return _op; }
		public ReplyStatus getStatus() { return _status; }
		public ErrorCode getError() { return _error; }
		public UUID getWorkerJvmInstanceId() { return _workerJvmInstanceId; }
		public long getWorkerPid() { return _workerPid; }
		public String getAcceptedStageSeal() { return _acceptedStageSeal; }
		public String getAcceptedSettingsDigest() { return _acceptedSettingsDigest; }
		public StreamFence[] getCompletedFences() { return copy(_completedFences); }
		public long getRootsRegistered() { return _rootsRegistered; }
		public long getChildrenRegistered() { return _childrenRegistered; }
		public long getTasksCompleted() { return _tasksCompleted; }
		public long getOutstanding() { return _outstanding; }
		public long getRejectedTasks() { return _rejectedTasks; }
		public long getFailedTasks() { return _failedTasks; }
		public boolean isRootsClosed() { return _rootsClosed; }
		public boolean isTerminal() { return _terminal; }
		public boolean isResetVerified() { return _resetVerified; }
		public UUID getResidencyLeaseId() { return _residencyLeaseId; }
		public SourceResidencyReceipt[] getSources() { return copy(_sources); }

		private void validate() {
			requireVersion(_protocolVersion);
			requireNonNull(_attemptId, "attemptId");
			requireNonNegative(_epoch, "epoch");
			requireNonNegative(_controlSequence, "controlSequence");
			requireNonNull(_op, "op");
			requireNonNull(_status, "status");
			requireNonNull(_error, "error");
			requireNonNull(_workerJvmInstanceId, "workerJvmInstanceId");
			requirePositive(_workerPid, "workerPid");
			requireBoundedString(_acceptedStageSeal, "acceptedStageSeal");
			requireBoundedString(_acceptedSettingsDigest, "acceptedSettingsDigest");
			requireArray(_completedFences, "completedFences");
			requireArray(_sources, "sources");
			validateFences(_completedFences);
			for(SourceResidencyReceipt source : _sources)
				requireNonNull(source, "sources entry");
			requireNonNegative(_rootsRegistered, "rootsRegistered");
			requireNonNegative(_childrenRegistered, "childrenRegistered");
			requireNonNegative(_tasksCompleted, "tasksCompleted");
			requireNonNegative(_outstanding, "outstanding");
			requireNonNegative(_rejectedTasks, "rejectedTasks");
			requireNonNegative(_failedTasks, "failedTasks");
			long registered = checkedAdd(_rootsRegistered, _childrenRegistered, "registered tasks");
			long resolved = checkedAdd(_tasksCompleted, _outstanding, "resolved tasks");
			if(registered != resolved)
				throw new IllegalArgumentException("registered tasks must equal completed plus outstanding");
			if(_failedTasks > _tasksCompleted)
				throw new IllegalArgumentException("failedTasks exceeds completed tasks");
			if(_terminal && (!_rootsClosed || _outstanding != 0))
				throw new IllegalArgumentException("terminal reply requires closed roots and no outstanding tasks");
			if((_status == ReplyStatus.ACK) != (_error == ErrorCode.NONE))
				throw new IllegalArgumentException("reply status/error mismatch");
			if(_status == ReplyStatus.ACK && (_failedTasks != 0 || _rejectedTasks != 0))
				throw new IllegalArgumentException("ACK reply cannot contain failed or rejected tasks");
			if(_resetVerified && (_op != ControlOp.RESET_WARM_STATE || _status != ReplyStatus.ACK))
				throw new IllegalArgumentException("resetVerified is invalid for this reply");
		}

		private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
			input.defaultReadObject();
			validateAfterDeserialization(this::validate);
		}

		private Object readResolve() throws ObjectStreamException {
			return new Reply(_protocolVersion, _attemptId, _epoch, _controlSequence, _op, _status, _error,
				_workerJvmInstanceId, _workerPid, _acceptedStageSeal, _acceptedSettingsDigest, _completedFences,
				_rootsRegistered, _childrenRegistered, _tasksCompleted, _outstanding, _rejectedTasks, _failedTasks,
				_rootsClosed, _terminal, _resetVerified, _residencyLeaseId, _sources);
		}
	}

	private static long checkedAdd(long left, long right, String name) {
		try {
			return Math.addExact(left, right);
		}
		catch(ArithmeticException ex) {
			throw new IllegalArgumentException(name + " overflow", ex);
		}
	}

	private static void validateFences(StreamFence[] fences) {
		Set<String> identities = new HashSet<>();
		for(StreamFence fence : fences) {
			requireNonNull(fence, "fence");
			String identity = fence.getStreamId() + ":" + fence.getNormalizedTid();
			if(!identities.add(identity))
				throw new IllegalArgumentException("duplicate stream fence");
		}
	}

	private static void validateSourceIds(String[] sourceIds) {
		Set<String> identities = new HashSet<>();
		for(String sourceId : sourceIds) {
			requireString(sourceId, "sourceId");
			if(!identities.add(sourceId))
				throw new IllegalArgumentException("duplicate sourceId");
		}
	}

	private static void requireVersion(int version) {
		if(version != PROTOCOL_VERSION)
			throw new IllegalArgumentException("unsupported phase protocol version: " + version);
	}

	private static void requireString(String value, String name) {
		if(value == null || value.isEmpty() || value.length() > MAX_STRING_LENGTH)
			throw new IllegalArgumentException(name + " must be non-empty and bounded");
	}

	private static void requireBoundedString(String value, String name) {
		if(value == null || value.length() > MAX_STRING_LENGTH)
			throw new IllegalArgumentException(name + " must be bounded");
	}

	private static void requireNonNull(Object value, String name) {
		if(value == null)
			throw new IllegalArgumentException(name + " must not be null");
	}

	private static void requireNonNegative(long value, String name) {
		if(value < 0)
			throw new IllegalArgumentException(name + " must be non-negative");
	}

	private static void requirePositive(long value, String name) {
		if(value <= 0)
			throw new IllegalArgumentException(name + " must be positive");
	}

	private static void requireArray(Object[] value, String name) {
		if(value == null || value.length > MAX_ARRAY_LENGTH)
			throw new IllegalArgumentException(name + " must be non-null and bounded");
	}

	private static void requireEmpty(Object[] value, String name) {
		if(value.length != 0)
			throw new IllegalArgumentException(name + " must be empty");
	}

	private static void requireNull(Object value, String name) {
		if(value != null)
			throw new IllegalArgumentException(name + " must be null");
	}

	private static StreamFence[] copy(StreamFence[] values) {
		return values == null ? null : Arrays.copyOf(values, values.length);
	}

	private static String[] copy(String[] values) {
		return values == null ? null : Arrays.copyOf(values, values.length);
	}

	private static SourceResidencyReceipt[] copy(SourceResidencyReceipt[] values) {
		return values == null ? null : Arrays.copyOf(values, values.length);
	}

	private static void validateAfterDeserialization(Validation validation) throws InvalidObjectException {
		try {
			validation.validate();
		}
		catch(IllegalArgumentException | ArithmeticException ex) {
			InvalidObjectException invalid = new InvalidObjectException(ex.getMessage());
			invalid.initCause(ex);
			throw invalid;
		}
	}

	@FunctionalInterface
	private interface Validation {
		void validate();
	}
}

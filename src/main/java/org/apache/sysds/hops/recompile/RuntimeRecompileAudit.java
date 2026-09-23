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

package org.apache.sysds.hops.recompile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.parser.DMLProgram;

/**
 * Bounded, opt-in observations of runtime recompilation. This audit is not a
 * completion barrier: in particular, it makes no claim about asynchronous work
 * started by compilation or execution.
 */
public final class RuntimeRecompileAudit {
	private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();
	private static final ThreadLocal<Binding> BINDING = new ThreadLocal<>();
	private static final Observation NO_OP = new Observation(null, null, 0, 0);

	private RuntimeRecompileAudit() {
		// utility class
	}

	public enum EventType {
		DAG_NORMAL,
		DAG_FORCED,
		HIERARCHY_NORMAL,
		HIERARCHY_FORCED
	}

	public enum InvalidReason {
		OWNER_MISMATCH,
		UNBOUND_THREAD,
		STALE_BINDING,
		BINDING_ORDER,
		PHASE_CLOSED,
		SESSION_CLOSED
	}

	public static Session start(DMLProgram expectedProgram, int maxEvents) {
		if(expectedProgram == null)
			throw new IllegalArgumentException("The expected DML program is required.");
		if(maxEvents < 1)
			throw new IllegalArgumentException("The event cap must be positive.");
		Session session = new Session(expectedProgram, maxEvents);
		if(!ACTIVE.compareAndSet(null, session))
			throw new IllegalStateException("A runtime recompile audit is already active.");
		return session;
	}

	static Observation observeDag(DMLProgram owner, boolean forced) {
		return observe(owner, forced ? EventType.DAG_FORCED : EventType.DAG_NORMAL);
	}

	static Observation observeHierarchy(DMLProgram owner, boolean forced) {
		return observe(owner, forced ? EventType.HIERARCHY_FORCED : EventType.HIERARCHY_NORMAL);
	}

	private static Observation observe(DMLProgram owner, EventType type) {
		Binding binding = BINDING.get();
		Session active = ACTIVE.get();
		if(binding == null) {
			if(active != null)
				active.invalidate(InvalidReason.UNBOUND_THREAD);
			return NO_OP;
		}
		if(binding.closed || binding.phase.closed || binding.session.closed || binding.session != active) {
			binding.session.invalidate(binding.session.closed ? InvalidReason.SESSION_CLOSED : InvalidReason.STALE_BINDING);
			if(active != null && active != binding.session)
				active.invalidate(InvalidReason.UNBOUND_THREAD);
			return NO_OP;
		}
		if(owner != binding.session.expectedProgram) {
			binding.session.invalidate(InvalidReason.OWNER_MISMATCH);
			return NO_OP;
		}
		return binding.session.admit(binding.phase, type);
	}

	public static final class Session implements AutoCloseable {
		private final Object lock = new Object();
		private final DMLProgram expectedProgram;
		private final int maxEvents;
		private final AtomicLong nextPhase = new AtomicLong();
		private final ArrayList<Event> events = new ArrayList<>();
		private final EnumSet<InvalidReason> invalidReasons = EnumSet.noneOf(InvalidReason.class);
		private boolean closed;
		private boolean truncated;
		private long droppedEvents;
		private long outstanding;

		private Session(DMLProgram expectedProgram, int maxEvents) {
			this.expectedProgram = expectedProgram;
			this.maxEvents = maxEvents;
		}

		public PhaseToken newPhase() {
			synchronized(lock) {
				if(closed)
					throw new IllegalStateException("The audit session is closed.");
				return new PhaseToken(this, nextPhase.incrementAndGet());
			}
		}

		public Binding bind(PhaseToken phase) {
			if(phase == null || phase.session != this)
				throw new IllegalArgumentException("The phase belongs to another audit session.");
			synchronized(lock) {
				if(closed)
					throw new IllegalStateException("The audit session is closed.");
				if(phase.closed) {
					invalidReasons.add(InvalidReason.PHASE_CLOSED);
					throw new IllegalStateException("The audit phase is closed.");
				}
				Binding previous = BINDING.get();
				if(previous != null && (previous.closed || previous.session.closed || previous.phase.closed)) {
					previous.session.invalidate(InvalidReason.STALE_BINDING);
					previous = null;
				}
				Binding binding = new Binding(this, phase, previous);
				BINDING.set(binding);
				return binding;
			}
		}

		public Snapshot snapshot() {
			synchronized(lock) {
				long dagNanos = 0;
				long hierarchyNanos = 0;
				for(Event event : events) {
					if(event.type == EventType.DAG_NORMAL || event.type == EventType.DAG_FORCED)
						dagNanos += event.durationNanos;
					else
						hierarchyNanos += event.durationNanos;
				}
				return new Snapshot(Collections.unmodifiableList(new ArrayList<>(events)),
					Collections.unmodifiableSet(EnumSet.copyOf(invalidReasons)), closed, truncated,
					droppedEvents, outstanding, dagNanos, hierarchyNanos);
			}
		}

		private Observation admit(PhaseToken phase, EventType type) {
			synchronized(lock) {
				if(closed) {
					invalidReasons.add(InvalidReason.SESSION_CLOSED);
					return NO_OP;
				}
				if(phase.closed) {
					invalidReasons.add(InvalidReason.PHASE_CLOSED);
					return NO_OP;
				}
				outstanding++;
				return new Observation(this, type, phase.id, System.nanoTime());
			}
		}

		private void finish(EventType type, long phaseId, long started, boolean success) {
			long duration = Math.max(0, System.nanoTime() - started);
			synchronized(lock) {
				outstanding--;
				if(events.size() < maxEvents)
					events.add(new Event(type, phaseId, success, duration));
				else {
					truncated = true;
					droppedEvents++;
				}
			}
		}

		private void invalidate(InvalidReason reason) {
			synchronized(lock) {
				invalidReasons.add(reason);
			}
		}

		@Override
		public void close() {
			synchronized(lock) {
				if(closed)
					return;
				closed = true;
			}
			ACTIVE.compareAndSet(this, null);
		}
	}

	public static final class PhaseToken implements AutoCloseable {
		private final Session session;
		private final long id;
		private volatile boolean closed;

		private PhaseToken(Session session, long id) {
			this.session = session;
			this.id = id;
		}

		public long getId() {
			return id;
		}

		@Override
		public void close() {
			synchronized(session.lock) {
				closed = true;
			}
		}
	}

	public static final class Binding implements AutoCloseable {
		private final Session session;
		private final PhaseToken phase;
		private final Binding previous;
		private boolean closed;

		private Binding(Session session, PhaseToken phase, Binding previous) {
			this.session = session;
			this.phase = phase;
			this.previous = previous;
		}

		@Override
		public void close() {
			if(closed)
				return;
			closed = true;
			if(BINDING.get() == this) {
				Binding restore = previous;
				while(restore != null && restore.closed)
					restore = restore.previous;
				if(restore == null)
					BINDING.remove();
				else
					BINDING.set(restore);
			}
			else
				session.invalidate(InvalidReason.BINDING_ORDER);
		}
	}

	static final class Observation implements AutoCloseable {
		private final Session session;
		private final EventType type;
		private final long phaseId;
		private final long started;
		private boolean success;
		private boolean closed;

		private Observation(Session session, EventType type, long phaseId, long started) {
			this.session = session;
			this.type = type;
			this.phaseId = phaseId;
			this.started = started;
		}

		void succeeded() {
			if(session != null)
				success = true;
		}

		@Override
		public void close() {
			if(session == null)
				return;
			if(closed)
				return;
			closed = true;
			session.finish(type, phaseId, started, success);
		}
	}

	public static final class Event {
		private final EventType type;
		private final long phaseId;
		private final boolean success;
		private final long durationNanos;

		private Event(EventType type, long phaseId, boolean success, long durationNanos) {
			this.type = type;
			this.phaseId = phaseId;
			this.success = success;
			this.durationNanos = durationNanos;
		}

		public EventType getType() { return type; }
		public long getPhaseId() { return phaseId; }
		public boolean isSuccess() { return success; }
		public long getDurationNanos() { return durationNanos; }
	}

	public static final class Snapshot {
		private final List<Event> events;
		private final java.util.Set<InvalidReason> invalidReasons;
		private final boolean closed;
		private final boolean truncated;
		private final long droppedEvents;
		private final long outstandingEvents;
		private final long dagDurationNanos;
		private final long hierarchyDurationNanos;

		private Snapshot(List<Event> events, java.util.Set<InvalidReason> invalidReasons, boolean closed,
			boolean truncated, long droppedEvents, long outstandingEvents, long dagDurationNanos,
			long hierarchyDurationNanos) {
			this.events = events;
			this.invalidReasons = invalidReasons;
			this.closed = closed;
			this.truncated = truncated;
			this.droppedEvents = droppedEvents;
			this.outstandingEvents = outstandingEvents;
			this.dagDurationNanos = dagDurationNanos;
			this.hierarchyDurationNanos = hierarchyDurationNanos;
		}

		public List<Event> getEvents() { return events; }
		public java.util.Set<InvalidReason> getInvalidReasons() { return invalidReasons; }
		public boolean isValid() {
			return closed && invalidReasons.isEmpty() && !truncated && outstandingEvents == 0;
		}
		public boolean isClosed() { return closed; }
		public boolean isTruncated() { return truncated; }
		public long getDroppedEvents() { return droppedEvents; }
		public long getOutstandingEvents() { return outstandingEvents; }
		public long getDagDurationNanos() { return dagDurationNanos; }
		public long getHierarchyDurationNanos() { return hierarchyDurationNanos; }
		public boolean isAsyncQuiescenceVerified() { return false; }
	}
}

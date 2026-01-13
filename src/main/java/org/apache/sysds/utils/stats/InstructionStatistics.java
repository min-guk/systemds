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

package org.apache.sysds.utils.stats;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.sysds.runtime.instructions.Instruction;

public class InstructionStatistics {
	private static final AtomicLong EXEC_ID = new AtomicLong(0);
	private static final Object WRITE_LOCK = new Object();
	private static final ThreadLocal<Deque<InstructionExecution>> STACK =
		ThreadLocal.withInitial(ArrayDeque::new);

	private static volatile boolean _enabled = false;
	private static BufferedWriter _writer = null;
	private static boolean _shutdownHookRegistered = false;

	private InstructionStatistics() {
		// utility class
	}

	public static boolean isEnabled() {
		return _enabled;
	}

	public static synchronized void initialize(String path) throws IOException {
		if (path == null || path.trim().isEmpty())
			throw new IOException("Instruction statistics path is empty.");

		shutdown();

		Path outPath = Paths.get(path).toAbsolutePath();
		Path parent = outPath.getParent();
		if (parent != null)
			Files.createDirectories(parent);

		_writer = Files.newBufferedWriter(outPath);
		writeHeader();
		_enabled = true;

		if (!_shutdownHookRegistered) {
			Runtime.getRuntime().addShutdownHook(new Thread(InstructionStatistics::shutdown));
			_shutdownHookRegistered = true;
		}
	}

	public static synchronized void shutdown() {
		_enabled = false;
		if (_writer != null) {
			try {
				_writer.flush();
				_writer.close();
			}
			catch (IOException ignored) {
				// best-effort shutdown
			}
			_writer = null;
		}
		EXEC_ID.set(0);
		STACK.remove();
	}

	public static void startInstruction(Instruction inst) {
		if (!_enabled || inst == null)
			return;
		InstructionExecution exec = new InstructionExecution(inst, EXEC_ID.incrementAndGet());
		STACK.get().push(exec);
	}

	public static void endInstruction(Instruction inst, long execNanos) {
		if (!_enabled)
			return;
		Deque<InstructionExecution> stack = STACK.get();
		if (stack.isEmpty())
			return;
		InstructionExecution exec = stack.pop();
		exec.execNanos = execNanos;
		writeRow(exec);
	}

	public static void addAcquireReadTime(long nanos) {
		InstructionExecution exec = current();
		if (exec == null)
			return;
		exec.acquireReadNanos += nanos;
		exec.acquireReadCount++;
	}

	public static void addAcquireModifyTime(long nanos) {
		InstructionExecution exec = current();
		if (exec == null)
			return;
		exec.acquireModifyNanos += nanos;
		exec.acquireModifyCount++;
	}

	public static void addReleaseTime(long nanos) {
		InstructionExecution exec = current();
		if (exec == null)
			return;
		exec.releaseNanos += nanos;
		exec.releaseCount++;
	}

	public static void addExportTime(long nanos) {
		InstructionExecution exec = current();
		if (exec == null)
			return;
		exec.exportNanos += nanos;
		exec.exportCount++;
	}

	private static InstructionExecution current() {
		if (!_enabled)
			return null;
		Deque<InstructionExecution> stack = STACK.get();
		return stack.isEmpty() ? null : stack.peek();
	}

	private static void writeHeader() throws IOException {
		synchronized (WRITE_LOCK) {
			_writer.write("exec_id,thread,inst_id,opcode,ext_opcode,inst_type,hop_id,lop_id,");
			_writer.write("dml_file,begin_line,end_line,begin_col,end_col,");
			_writer.write("exec_time_ns,acquire_read_time_ns,acquire_read_count,");
			_writer.write("acquire_modify_time_ns,acquire_modify_count,");
			_writer.write("release_time_ns,release_count,");
			_writer.write("export_time_ns,export_count,inst_string");
			_writer.newLine();
		}
	}

	private static void writeRow(InstructionExecution exec) {
		if (_writer == null)
			return;
		String line = toCsv(exec);
		synchronized (WRITE_LOCK) {
			try {
				_writer.write(line);
				_writer.newLine();
			}
			catch (IOException ignored) {
				// best-effort; avoid breaking runtime execution
			}
		}
	}

	private static String toCsv(InstructionExecution exec) {
		StringBuilder sb = new StringBuilder(512);
		appendLong(sb, exec.execId);
		appendString(sb, exec.threadName);
		appendLong(sb, exec.instId);
		appendString(sb, exec.opcode);
		appendString(sb, exec.extOpcode);
		appendString(sb, exec.instType);
		appendLong(sb, exec.hopId);
		appendLong(sb, exec.lopId);
		appendString(sb, exec.dmlFile);
		appendInt(sb, exec.beginLine);
		appendInt(sb, exec.endLine);
		appendInt(sb, exec.beginCol);
		appendInt(sb, exec.endCol);
		appendLong(sb, exec.execNanos);
		appendLong(sb, exec.acquireReadNanos);
		appendLong(sb, exec.acquireReadCount);
		appendLong(sb, exec.acquireModifyNanos);
		appendLong(sb, exec.acquireModifyCount);
		appendLong(sb, exec.releaseNanos);
		appendLong(sb, exec.releaseCount);
		appendLong(sb, exec.exportNanos);
		appendLong(sb, exec.exportCount);
		appendString(sb, exec.instString);
		return sb.toString();
	}

	private static void appendInt(StringBuilder sb, int value) {
		if (sb.length() > 0)
			sb.append(',');
		sb.append(value);
	}

	private static void appendLong(StringBuilder sb, long value) {
		if (sb.length() > 0)
			sb.append(',');
		sb.append(value);
	}

	private static void appendString(StringBuilder sb, String value) {
		if (sb.length() > 0)
			sb.append(',');
		sb.append(escapeCsv(value));
	}

	private static String escapeCsv(String value) {
		if (value == null)
			return "";
		boolean needsQuote = value.indexOf(',') >= 0
			|| value.indexOf('"') >= 0
			|| value.indexOf('\n') >= 0
			|| value.indexOf('\r') >= 0;
		if (!needsQuote)
			return value;
		String escaped = value.replace("\"", "\"\"");
		return "\"" + escaped + "\"";
	}

	private static class InstructionExecution {
		private final long execId;
		private final String threadName;
		private final long instId;
		private final String opcode;
		private final String extOpcode;
		private final String instType;
		private final long hopId;
		private final long lopId;
		private final String dmlFile;
		private final int beginLine;
		private final int endLine;
		private final int beginCol;
		private final int endCol;
		private final String instString;
		private long execNanos;
		private long acquireReadNanos;
		private long acquireReadCount;
		private long acquireModifyNanos;
		private long acquireModifyCount;
		private long releaseNanos;
		private long releaseCount;
		private long exportNanos;
		private long exportCount;

		private InstructionExecution(Instruction inst, long execId) {
			this.execId = execId;
			this.threadName = Thread.currentThread().getName();
			this.instId = inst.getInstID();
			this.opcode = inst.getOpcode();
			this.extOpcode = inst.getExtendedOpcode();
			this.instType = inst.getType().toString();
			this.hopId = inst.getHopID();
			this.lopId = inst.getLopID();
			this.dmlFile = inst.getFilename();
			this.beginLine = inst.getBeginLine();
			this.endLine = inst.getEndLine();
			this.beginCol = inst.getBeginColumn();
			this.endCol = inst.getEndColumn();
			this.instString = inst.getInstructionString();
		}
	}
}

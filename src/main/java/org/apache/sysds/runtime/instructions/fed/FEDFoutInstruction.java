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

package org.apache.sysds.runtime.instructions.fed;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

public class FEDFoutInstruction extends FEDInstruction {
	private final CPOperand _input;
	private final CPOperand _anchor;
	private final CPOperand _output;
	private final FType _fTypeHint;
	private static final boolean DEBUG_KMEANS = Boolean.getBoolean("sysds.debug.kmeans");

	private FEDFoutInstruction(CPOperand input, CPOperand anchor, CPOperand output, FType fTypeHint, String opcode,
		String istr) {
		super(FEDType.Fout, opcode, istr);
		_input = input;
		_anchor = anchor;
		_output = output;
		_fTypeHint = fTypeHint;
	}

	public static FEDFoutInstruction parseInstruction(String str) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		if (parts.length != 5)
			throw new DMLRuntimeException("Invalid number of operands in federated fout instruction: " + str);
		CPOperand input = new CPOperand(parts[1]);
		CPOperand anchor = new CPOperand(parts[2]);
		CPOperand output = new CPOperand(parts[3]);
		FType fType = parseFType(parts[4]);
		return new FEDFoutInstruction(input, anchor, output, fType, parts[0], str);
	}

	private static FType parseFType(String fTypeLiteral) {
		if (fTypeLiteral == null)
			return FType.FULL;
		String upper = fTypeLiteral.trim().toUpperCase();
		switch (upper) {
			case "ROW":
				return FType.ROW;
			case "COL":
				return FType.COL;
			case "FULL":
				return FType.FULL;
			case "BROADCAST":
				return FType.BROADCAST;
			default:
				throw new DMLRuntimeException("fed_fout expects ftype literal ROW|COL|FULL|BROADCAST but found " + fTypeLiteral);
		}
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		MatrixObject in = ec.getMatrixObject(_input);
		FederationMap anchorMap = null;
		boolean anchorLiteral = !_anchor.isMatrix() || !ec.containsVariable(_anchor.getName());
		if (!anchorLiteral) {
			MatrixObject anchor = ec.getMatrixObject(_anchor);
			anchorMap = anchor.getFedMapping();
			if (!anchor.isFederated() || anchorMap == null) {
				FederationMap fallback = FederationUtils.getAnchorMap(_anchor.getName());
				if (fallback != null)
					anchorMap = fallback;
				else {
					FederationMap derived = FederationUtils.buildAnchorMapFromKey(
						FederationUtils.getAnchorKey(_anchor.getName()));
					if (derived != null) {
						anchorMap = derived;
						FederationUtils.registerAnchorMap(_anchor.getName(), anchorMap);
					}
					else {
						throw new DMLRuntimeException("fed_fout requires a federated anchor: " + _anchor.getName());
					}
				}
			}
		}
		else {
			String anchorKey = _anchor.getName();
			// Resolve "VAR:<name>" anchors against live runtime variables first.
			if (anchorKey != null && anchorKey.startsWith("VAR:")) {
				String varName = anchorKey.substring("VAR:".length());
				int pipeIx = varName.indexOf('|');
				if (pipeIx >= 0)
					varName = varName.substring(0, pipeIx);
				if (ec.containsVariable(varName)) {
					MatrixObject anchor = ec.getMatrixObject(varName);
					if (anchor != null && anchor.isFederated() && anchor.getFedMapping() != null)
						anchorMap = anchor.getFedMapping();
				}
			}
			if (anchorMap == null) {
				FederationMap fallback = FederationUtils.getAnchorMap(anchorKey);
				if (fallback != null) {
					anchorMap = fallback;
				}
				else {
					FederationMap derived = FederationUtils.buildAnchorMapFromKey(anchorKey);
					if (derived != null) {
						anchorMap = derived;
						FederationUtils.registerAnchorMap(anchorKey, anchorMap);
					}
					else {
						throw new DMLRuntimeException("fed_fout requires a federated anchor: " + anchorKey);
					}
				}
			}
		}
		int numWorkers = anchorMap.getSize();
		if (numWorkers <= 0)
			throw new DMLRuntimeException("fed_fout cannot materialize: no federated parent/worker pool (empty anchor map)");
		FType anchorType = anchorMap.getType();
		if (anchorType == FType.PART || anchorType == FType.OTHER)
			throw new DMLRuntimeException("fed_fout does not support anchor type " + anchorType);

		long rlen = in.getNumRows();
		long clen = in.getNumColumns();
		if (in.isFederated()) {
			FType outTypeHint = _fTypeHint != null ? _fTypeHint : FType.FULL;
			if (outTypeHint == FType.PART || outTypeHint == FType.OTHER)
				throw new DMLRuntimeException("fed_fout does not support ftype " + outTypeHint);

			FederationMap inMap = in.getFedMapping();
			if (inMap == null || inMap.getSize() == 0)
				throw new DMLRuntimeException("fed_fout expects a non-empty federated input map: " + _input.getName());

			// Ensure the input is hosted on the same worker pool as the anchor.
			java.util.HashSet<java.net.InetSocketAddress> inWorkers = new java.util.HashSet<>();
			for (Pair<FederatedRange, FederatedData> e : inMap.getMap())
				inWorkers.add(e.getValue().getAddress());
			java.util.HashSet<java.net.InetSocketAddress> anchorWorkers = new java.util.HashSet<>();
			for (Pair<FederatedRange, FederatedData> e : anchorMap.getMap())
				anchorWorkers.add(e.getValue().getAddress());
			if (!inWorkers.equals(anchorWorkers))
				throw new DMLRuntimeException("fed_fout cannot reuse federated input " + _input.getName()
					+ " because its worker pool differs from anchor " + _anchor.getName());

			// Determine output dimensions if not already known.
			if (rlen < 0 || clen < 0) {
				long maxR = 0, maxC = 0;
				for (Pair<FederatedRange, FederatedData> e : inMap.getMap()) {
					FederatedRange range = e.getKey();
					if (range == null)
						continue;
					long[] end = range.getEndDims();
					if (end != null && end.length >= 2) {
						maxR = Math.max(maxR, end[0]);
						maxC = Math.max(maxC, end[1]);
					}
				}
				rlen = maxR;
				clen = maxC;
			}
			if (rlen < 0 || clen < 0)
				throw new DMLRuntimeException("fed_fout requires known output dimensions: rlen=" + rlen + " clen=" + clen);

				// If the input is already federated, treat fed_fout as a no-op provided the types are compatible.
				FType inType = inMap.getType();
				FType mapType = (outTypeHint == FType.BROADCAST) ? FType.BROADCAST : outTypeHint;
				boolean compatible = (inType == mapType)
					|| (mapType == FType.BROADCAST && (inType == FType.FULL || inType == FType.BROADCAST));
				// BROADCAST is a special case of FULL replication; allow cheap metadata conversion.
				compatible |= (mapType == FType.FULL && inType == FType.BROADCAST);
				if (!compatible)
					throw new DMLRuntimeException("fed_fout cannot convert federated input " + _input.getName()
						+ " of type " + inType + " to " + mapType + " without materialization");

			MatrixObject out = ec.getMatrixObject(_output);
			FederationMap outMap = (inType == mapType) ? inMap : new FederationMap(inMap.getID(), inMap.getMap(), mapType);
			out.setFedMapping(outMap);
			out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] fed_fout in=" + _input.getName()
					+ " out=" + _output.getName()
					+ " dims=" + rlen + "x" + clen
					+ " hint=" + outTypeHint
					+ " mapType=" + outMap.getType()
					+ " reuseFed=true");
			}
			return;
		}
		if (rlen < 0 || clen < 0) {
			MatrixBlock block = in.acquireRead();
			rlen = block.getNumRows();
			clen = block.getNumColumns();
			in.release();
		}
		if (rlen < 0 || clen < 0)
			throw new DMLRuntimeException("fed_fout requires known output dimensions: rlen=" + rlen + " clen=" + clen);
		long nnz = in.getNnz();
		long inputUniqueId = in.getUniqueID();
		long inputMutationVersion = in.getMutationVersion();
		long anchorMapId = anchorMap.getID();

		FType outTypeHint = _fTypeHint != null ? _fTypeHint : FType.FULL;
		if (outTypeHint == FType.PART || outTypeHint == FType.OTHER)
			throw new DMLRuntimeException("fed_fout does not support ftype " + outTypeHint);

		final boolean broadcastOut = (outTypeHint == FType.BROADCAST);
		FType mapType = broadcastOut ? FType.BROADCAST : outTypeHint;
		FType materializeType = broadcastOut ? FType.FULL : outTypeHint;

		if (materializeType == FType.ROW && rlen < numWorkers) {
			materializeType = FType.FULL;
			mapType = FType.BROADCAST;
		}
		else if (materializeType == FType.COL && clen < numWorkers) {
			materializeType = FType.FULL;
			mapType = FType.BROADCAST;
		}

		FType cacheMapType = FEDLocalMaterializeUtil.normalizeReplicatedMapType(materializeType, mapType, numWorkers);
		FederationMap cached = FederationUtils.getRefedReuseMap(inputUniqueId, inputMutationVersion,
			rlen, clen, nnz, anchorMapId, cacheMapType);
		if (cached != null) {
			MatrixObject out = ec.getMatrixObject(_output);
			out.setFedMapping(cached);
			out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), nnz);
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] fed_fout reuse-local in=" + _input.getName()
					+ " out=" + _output.getName()
					+ " dims=" + rlen + "x" + clen
					+ " hint=" + outTypeHint
					+ " mapType=" + cached.getType()
					+ " reuseFed=false");
			}
			return;
		}

		FederationMap outMap = FEDLocalMaterializeUtil.materializeLocalToAnchor(getTID(), in, anchorMap,
			materializeType, mapType, rlen, clen, true, "fed_fout");

		MatrixObject out = ec.getMatrixObject(_output);
		out.setFedMapping(outMap);
		out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
		FederationUtils.putRefedReuseMap(inputUniqueId, inputMutationVersion,
			rlen, clen, nnz, anchorMapId, outMap.getType(), outMap);
		if (DEBUG_KMEANS) {
			System.out.println("[DBG-KMEANS] fed_fout in=" + _input.getName()
				+ " out=" + _output.getName()
				+ " dims=" + rlen + "x" + clen
				+ " hint=" + outTypeHint
				+ " mapType=" + outMap.getType()
				+ " reuseFed=false");
		}
	}
}

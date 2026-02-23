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

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

public class FEDRefedInstruction extends FEDInstruction {
	private final CPOperand _input;
	private final CPOperand _anchor;
	private final CPOperand _output;
	private static final boolean DEBUG_KMEANS = Boolean.getBoolean("sysds.debug.kmeans");

	private FEDRefedInstruction(CPOperand input, CPOperand anchor, CPOperand output, String opcode, String istr) {
		super(FEDType.Refed, opcode, istr);
		_input = input;
		_anchor = anchor;
		_output = output;
	}

	public static FEDRefedInstruction parseInstruction(String str) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		if (parts.length != 4)
			throw new DMLRuntimeException("Invalid number of operands in federated refed instruction: " + str);
		CPOperand input = new CPOperand(parts[1]);
		CPOperand anchor = new CPOperand(parts[2]);
		CPOperand output = new CPOperand(parts[3]);
		return new FEDRefedInstruction(input, anchor, output, parts[0], str);
	}

	private static boolean matchesMaterializeToAnchorLayout(FederationMap inMap, FederationMap anchorMap,
		FType materializeType, long rlen, long clen) {
		if (inMap == null || anchorMap == null)
			return false;
		int numWorkers = anchorMap.getSize();
		if (numWorkers <= 0 || inMap.getSize() != numWorkers)
			return false;

		FederatedRange[] inRanges = inMap.getFederatedRanges();
		FederatedData[] inData = inMap.getFederatedData();
		FederatedData[] anchorData = anchorMap.getFederatedData();
		if (inRanges.length != numWorkers || inData.length != numWorkers || anchorData.length != numWorkers)
			return false;

		long[] rowBeg = new long[numWorkers];
		long[] rowEnd = new long[numWorkers];
		long[] colBeg = new long[numWorkers];
		long[] colEnd = new long[numWorkers];
		if (materializeType == FType.FULL) {
			for (int i = 0; i < numWorkers; i++) {
				rowBeg[i] = 0;
				rowEnd[i] = rlen;
				colBeg[i] = 0;
				colEnd[i] = clen;
			}
		}
		else if (materializeType == FType.ROW) {
			long base = rlen / numWorkers;
			long rem = rlen % numWorkers;
			long pos = 0;
			for (int i = 0; i < numWorkers; i++) {
				long size = base + (i < rem ? 1 : 0);
				rowBeg[i] = pos;
				rowEnd[i] = pos + size;
				colBeg[i] = 0;
				colEnd[i] = clen;
				pos += size;
			}
		}
		else if (materializeType == FType.COL) {
			long base = clen / numWorkers;
			long rem = clen % numWorkers;
			long pos = 0;
			for (int i = 0; i < numWorkers; i++) {
				long size = base + (i < rem ? 1 : 0);
				rowBeg[i] = 0;
				rowEnd[i] = rlen;
				colBeg[i] = pos;
				colEnd[i] = pos + size;
				pos += size;
			}
		}
		else {
			return false;
		}

		for (int i = 0; i < numWorkers; i++) {
			if (inData[i] == null || anchorData[i] == null || !inData[i].getAddress().equals(anchorData[i].getAddress()))
				return false;
			FederatedRange range = inRanges[i];
			if (range == null)
				return false;
			long[] begin = range.getBeginDims();
			long[] end = range.getEndDims();
			if (begin == null || end == null || begin.length < 2 || end.length < 2)
				return false;
			if (begin[0] != rowBeg[i] || end[0] != rowEnd[i] || begin[1] != colBeg[i] || end[1] != colEnd[i])
				return false;
		}

		return true;
	}

	private static FederationMap findUniqueWorkerPoolAnchor(ExecutionContext ec) {
		if (ec == null)
			return null;
		org.apache.sysds.runtime.controlprogram.LocalVariableMap vars = ec.getVariables();
		if (vars == null || vars.keySet().isEmpty())
			return null;

		java.util.HashSet<java.net.InetSocketAddress> pool = null;
		FederationMap anchor = null;

		for (String name : vars.keySet()) {
			org.apache.sysds.runtime.instructions.cp.Data dat = vars.get(name);
			if (!(dat instanceof MatrixObject))
				continue;
			MatrixObject mo = (MatrixObject) dat;
			if (!mo.isFederated() || mo.getFedMapping() == null || mo.getFedMapping().getSize() == 0)
				continue;
			FederationMap map = mo.getFedMapping();
			java.util.HashSet<java.net.InetSocketAddress> workers = new java.util.HashSet<>();
			for (FederatedData d : map.getFederatedData()) {
				if (d != null && d.getAddress() != null)
					workers.add(d.getAddress());
			}
			if (workers.isEmpty())
				continue;
			if (pool == null) {
				pool = workers;
				anchor = map;
			}
			else if (!pool.equals(workers)) {
				return null;
			}
		}

		return anchor;
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
						FederationMap any = findUniqueWorkerPoolAnchor(ec);
						if (any != null)
							anchorMap = any;
						else
							throw new DMLRuntimeException("fed_refed requires a federated anchor: " + _anchor.getName());
					}
				}
			}
		}
		else {
			String anchorKey = _anchor.getName();
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
					throw new DMLRuntimeException("fed_refed requires a federated anchor: " + anchorKey);
				}
			}
		}

		FType fType = anchorMap.getType();
		if (fType == FType.PART || fType == FType.OTHER)
			throw new DMLRuntimeException("fed_refed does not support anchor type " + fType);

		long rlen = in.getNumRows();
		long clen = in.getNumColumns();
		if (in.isFederated()) {
			FederationMap inMap = in.getFedMapping();
			if (inMap == null || inMap.getSize() == 0)
				throw new DMLRuntimeException("fed_refed expects a non-empty federated input map: " + _input.getName());

			// Ensure the input is hosted on the same worker pool as the anchor.
			java.util.HashSet<java.net.InetSocketAddress> inWorkers = new java.util.HashSet<>();
			for (FederatedData d : inMap.getFederatedData())
				inWorkers.add(d.getAddress());
			java.util.HashSet<java.net.InetSocketAddress> anchorWorkers = new java.util.HashSet<>();
			for (FederatedData d : anchorMap.getFederatedData())
				anchorWorkers.add(d.getAddress());
			if (!inWorkers.equals(anchorWorkers))
				throw new DMLRuntimeException("fed_refed cannot reuse federated input " + _input.getName()
					+ " because its worker pool differs from anchor " + _anchor.getName());

			// Determine output dimensions if not already known.
			if (rlen < 0 || clen < 0) {
				long maxR = 0, maxC = 0;
				for (FederatedRange range : inMap.getFederatedRanges()) {
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
				throw new DMLRuntimeException("fed_refed requires known output dimensions: rlen=" + rlen + " clen=" + clen);

			long maxRow = anchorMap.getMaxIndexInRange(0);
			long maxCol = anchorMap.getMaxIndexInRange(1);

			// If the federated input already matches the desired refederation layout, treat fed_refed as a no-op.
			if (maxRow == rlen && maxCol == clen && (fType == FType.ROW || fType == FType.COL)) {
				boolean aligned = inMap.getType() == fType
					&& inMap.getSize() == anchorMap.getSize()
					&& inMap.isAligned(anchorMap, false);
				if (!aligned)
					throw new DMLRuntimeException("fed_refed cannot refederate federated input " + _input.getName()
						+ " to anchor " + _anchor.getName() + " because it is not aligned");
				MatrixObject out = ec.getMatrixObject(_output);
				out.setFedMapping(inMap);
				out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
				if (DEBUG_KMEANS) {
					System.out.println("[DBG-KMEANS] fed_refed reuse-fed in=" + _input.getName()
						+ " out=" + _output.getName()
						+ " dims=" + rlen + "x" + clen
						+ " anchor=" + _anchor.getName()
						+ " type=" + inMap.getType());
				}
				return;
			}

			int numWorkers = anchorMap.getSize();
			FType materializeType = (fType == FType.ROW || fType == FType.COL) ? fType : FType.FULL;
			FType mapType = materializeType;
			if (materializeType == FType.ROW && rlen < numWorkers) {
				materializeType = FType.FULL;
				mapType = FType.BROADCAST;
			}
			else if (materializeType == FType.COL && clen < numWorkers) {
				materializeType = FType.FULL;
				mapType = FType.BROADCAST;
			}
			FType expectedType = FEDLocalMaterializeUtil.normalizeReplicatedMapType(materializeType, mapType, numWorkers);
			FType inType = inMap.getType();
			boolean compatible = (inType == expectedType)
				|| (expectedType == FType.BROADCAST && (inType == FType.FULL || inType == FType.BROADCAST))
				|| (expectedType == FType.FULL && inType == FType.BROADCAST);
			if (!compatible)
				throw new DMLRuntimeException("fed_refed cannot reuse federated input " + _input.getName()
					+ " of type " + inType + " for anchor " + _anchor.getName() + " type " + expectedType);

			if (!matchesMaterializeToAnchorLayout(inMap, anchorMap, materializeType, rlen, clen))
				throw new DMLRuntimeException("fed_refed cannot reuse federated input " + _input.getName()
					+ " because it does not match expected refederation layout for anchor " + _anchor.getName());

			MatrixObject out = ec.getMatrixObject(_output);
			FederationMap outMap = (inType == expectedType) ? inMap : new FederationMap(inMap.getID(), inMap.getMap(), expectedType);
			out.setFedMapping(outMap);
			out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] fed_refed reuse-fed in=" + _input.getName()
					+ " out=" + _output.getName()
					+ " dims=" + rlen + "x" + clen
					+ " anchor=" + _anchor.getName()
					+ " type=" + outMap.getType());
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
			throw new DMLRuntimeException("fed_refed requires known output dimensions: rlen=" + rlen + " clen=" + clen);
		MatrixObject out = ec.getMatrixObject(_output);
		long nnz = in.getNnz();
		long inputUniqueId = in.getUniqueID();
		long inputMutationVersion = in.getMutationVersion();
		long anchorMapId = anchorMap.getID();
		FederationMap cached = FederationUtils.getRefedReuseMap(inputUniqueId, inputMutationVersion,
			rlen, clen, nnz, anchorMapId, fType);
		if (cached != null) {
			out.setFedMapping(cached);
			out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), nnz);
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] fed_refed reuse in=" + _input.getName()
					+ " out=" + _output.getName()
					+ " dims=" + rlen + "x" + clen
					+ " anchor=" + _anchor.getName()
					+ " type=" + anchorMap.getType());
			}
			return;
		}

		long maxRow = anchorMap.getMaxIndexInRange(0);
		long maxCol = anchorMap.getMaxIndexInRange(1);
		if (maxRow != rlen || maxCol != clen) {
			FType materializeType = (fType == FType.ROW || fType == FType.COL) ? fType : FType.FULL;
			FType mapType = materializeType;
			if (materializeType == FType.ROW && rlen < anchorMap.getSize()) {
				materializeType = FType.FULL;
				mapType = FType.BROADCAST;
			}
			else if (materializeType == FType.COL && clen < anchorMap.getSize()) {
				materializeType = FType.FULL;
				mapType = FType.BROADCAST;
			}
			out.setFedMapping(FEDLocalMaterializeUtil.materializeLocalToAnchor(getTID(), in, anchorMap,
				materializeType, mapType, rlen, clen, false, "fed_refed"));
			out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
			FederationUtils.putRefedReuseMap(inputUniqueId, inputMutationVersion,
				rlen, clen, nnz, anchorMapId, fType, out.getFedMapping());
			return;
		}

		long outId;
		if (fType == FType.ROW || fType == FType.COL) {
			FederatedRequest[] fr = anchorMap.broadcastSliced(in, false);
			if (fr.length == 0)
				throw new DMLRuntimeException("fed_refed cannot refederate to an empty anchor map");
			outId = fr[0].getID();
			anchorMap.execute(getTID(), true, fr, new FederatedRequest[0]);
		}
		else {
			if (anchorMap.getSize() == 0)
				throw new DMLRuntimeException("fed_refed cannot refederate to an empty anchor map");
			outId = FederationUtils.getNextFedDataID();
			MatrixBlock block = in.acquireReadAndRelease();
			FederatedRequest fr = new FederatedRequest(RequestType.PUT_VAR, outId, block);
			anchorMap.execute(getTID(), true, fr);
		}

		out.setFedMapping(anchorMap.copyWithNewID(outId));
		out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
		FederationUtils.putRefedReuseMap(inputUniqueId, inputMutationVersion,
			rlen, clen, nnz, anchorMapId, fType, out.getFedMapping());
		if (DEBUG_KMEANS) {
			System.out.println("[DBG-KMEANS] fed_refed in=" + _input.getName()
				+ " out=" + _output.getName()
				+ " dims=" + rlen + "x" + clen
				+ " anchor=" + _anchor.getName()
				+ " type=" + anchorMap.getType());
		}
	}
}

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
	private final FType _materializationFType;
	private static final boolean DEBUG_KMEANS = Boolean.getBoolean("sysds.debug.kmeans");

	private FEDRefedInstruction(CPOperand input, CPOperand anchor, CPOperand output,
		FType materializationFType, String opcode, String istr) {
		super(FEDType.Refed, null, opcode, istr, FederatedOutput.FOUT);
		_input = input;
		_anchor = anchor;
		_output = output;
		_materializationFType = materializationFType;
	}

	public static FEDRefedInstruction parseInstruction(String str) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		if (parts.length != 4 && parts.length != 5)
			throw new DMLRuntimeException("Invalid number of operands in federated refed instruction: " + str);
		CPOperand input = new CPOperand(parts[1]);
		CPOperand anchor = new CPOperand(parts[2]);
		CPOperand output = new CPOperand(parts[3]);
		FType materializationFType = null;
		if(parts.length == 5) {
			try {
				materializationFType = FType.valueOf(parts[4]);
			}
			catch(IllegalArgumentException ex) {
				throw new DMLRuntimeException("Invalid fed_refed materialization type " + parts[4], ex);
			}
		}
		return new FEDRefedInstruction(input, anchor, output, materializationFType, parts[0], str);
	}

	public FType getMaterializationFType() {
		return _materializationFType;
	}

	@Override
	public String getOutputVariableName() {
		return _output.getName();
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
					else
						throw new DMLRuntimeException("fed_refed requires its selected federated anchor: "
							+ _anchor.getName());
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

		FType anchorFType = FEDLocalMaterializeUtil.declaredAnchorType(anchorMap);
		FType fType = _materializationFType != null ? _materializationFType : anchorFType;
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

			int numWorkers = anchorMap.getSize();
			FType materializeType = (fType == FType.ROW || fType == FType.COL) ? fType : FType.FULL;
			FType mapType = fType == FType.BROADCAST ? FType.BROADCAST : materializeType;
			FType expectedType = FEDLocalMaterializeUtil.normalizeReplicatedMapType(materializeType, mapType, numWorkers);
			FType inType = inMap.getType();
			boolean compatible = FEDLocalMaterializeUtil.matchesPlannedLayout(
				inMap, anchorMap, materializeType, expectedType, rlen, clen);
			if (!compatible)
				throw new DMLRuntimeException("fed_refed cannot reuse federated input " + _input.getName()
					+ " of type " + inType + " for anchor " + _anchor.getName() + " type " + expectedType);

			MatrixObject out = ec.getMatrixObject(_output);
			FederationMap outMap = (inType == expectedType)
				? inMap
				: new FederationMap(inMap.getID(), inMap.getMap(), expectedType);
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
		String inputKey = in.getFileName();
		if (inputKey == null || inputKey.isEmpty())
			inputKey = _input.getName();
		int numWorkers = anchorMap.getSize();
		long maxRow = anchorMap.getMaxIndexInRange(0);
		long maxCol = anchorMap.getMaxIndexInRange(1);
		FType cacheMapType = fType;
		String layoutSig;
		boolean preservesAnchorLayout = fType == anchorFType && maxRow == rlen && maxCol == clen;
		if (preservesAnchorLayout) {
			layoutSig = FederationUtils.deriveFedLayoutSignature(anchorMap);
		}
		else {
			FType materializeType = (fType == FType.ROW || fType == FType.COL) ? fType : FType.FULL;
			FType mapType = fType == FType.BROADCAST ? FType.BROADCAST : materializeType;
			cacheMapType = FEDLocalMaterializeUtil.normalizeReplicatedMapType(materializeType, mapType, numWorkers);
			layoutSig = FederationUtils.deriveMaterializedLayoutSignature(anchorMap, cacheMapType, rlen, clen);
		}
		if (DEBUG_KMEANS) {
			System.out.println("[DBG-KMEANS] fed_refed cachekey in=" + _input.getName()
				+ " inputKey=" + inputKey
				+ " uid=" + inputUniqueId
				+ " mut=" + inputMutationVersion
				+ " dims=" + rlen + "x" + clen
				+ " nnz=" + nnz
				+ " anchor=" + _anchor.getName()
				+ " layoutSig=" + layoutSig
				+ " outType=" + cacheMapType);
		}
		FederationMap cached = FederationUtils.getRefedReuseMap(inputKey, inputUniqueId, inputMutationVersion,
			rlen, clen, nnz, layoutSig, cacheMapType);
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

		if (!preservesAnchorLayout) {
			FType materializeType = (fType == FType.ROW || fType == FType.COL) ? fType : FType.FULL;
			FType mapType = fType == FType.BROADCAST ? FType.BROADCAST : materializeType;
			out.setFedMapping(FEDLocalMaterializeUtil.materializeLocalToAnchor(getTID(), in, anchorMap,
				materializeType, mapType, rlen, clen));
			out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
			FederationUtils.putRefedReuseMap(inputKey, inputUniqueId, inputMutationVersion,
				rlen, clen, nnz, layoutSig, cacheMapType, out.getFedMapping());
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
		FederationUtils.putRefedReuseMap(inputKey, inputUniqueId, inputMutationVersion,
			rlen, clen, nnz, layoutSig, cacheMapType, out.getFedMapping());
		if (DEBUG_KMEANS) {
			System.out.println("[DBG-KMEANS] fed_refed in=" + _input.getName()
				+ " out=" + _output.getName()
				+ " dims=" + rlen + "x" + clen
				+ " anchor=" + _anchor.getName()
				+ " type=" + anchorMap.getType()
				+ " layoutSig=" + layoutSig);
		}
	}
}

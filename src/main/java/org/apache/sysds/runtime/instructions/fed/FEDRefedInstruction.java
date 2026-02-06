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
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
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

	@Override
	public void processInstruction(ExecutionContext ec) {
		MatrixObject in = ec.getMatrixObject(_input);
		FederationMap anchorMap = null;
		boolean anchorLiteral = !_anchor.isMatrix() || !ec.containsVariable(_anchor.getName());
		if (!anchorLiteral) {
			MatrixObject anchor = ec.getMatrixObject(_anchor);
			if (anchor.isFederated()) {
				anchorMap = anchor.getFedMapping();
			}
			else {
				throw new DMLRuntimeException("fed_refed requires a federated anchor: " + _anchor.getName());
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

		if (in.isFederated())
			throw new DMLRuntimeException("fed_refed expects a local input but found federated input: " + _input.getName());

		long rlen = in.getNumRows();
		long clen = in.getNumColumns();
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

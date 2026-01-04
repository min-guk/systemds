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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
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
		if (in.isFederated())
			throw new DMLRuntimeException("fed_refed expects a local input but found federated input: " + _input.getName());

		MatrixObject anchor = ec.getMatrixObject(_anchor);
		if (!anchor.isFederated())
			throw new DMLRuntimeException("fed_refed requires a federated anchor: " + _anchor.getName());

		FederationMap anchorMap = anchor.getFedMapping();
		FType fType = anchorMap.getType();
		if (fType == FType.PART || fType == FType.OTHER)
			throw new DMLRuntimeException("fed_refed does not support anchor type " + fType);

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

		long maxRow = anchorMap.getMaxIndexInRange(0);
		long maxCol = anchorMap.getMaxIndexInRange(1);
		if (maxRow != rlen || maxCol != clen) {
			MatrixObject out = ec.getMatrixObject(_output);
			materializeFallback(getTID(), in, anchorMap, fType, out, rlen, clen);
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

		MatrixObject out = ec.getMatrixObject(_output);
		out.setFedMapping(anchorMap.copyWithNewID(outId));
		out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
	}

	private static void materializeFallback(long tid, MatrixObject in, FederationMap anchorMap, FType anchorType,
		MatrixObject out, long rlen, long clen) {
		if (anchorMap.getSize() == 0)
			throw new DMLRuntimeException("fed_fout cannot materialize: no federated parent/worker pool (empty anchor map)");

		FType outType = (anchorType == FType.ROW || anchorType == FType.COL) ? anchorType : FType.FULL;
		long[] rowBeg = null;
		long[] rowEnd = null;
		long[] colBeg = null;
		long[] colEnd = null;
		int numWorkers = anchorMap.getSize();
		if (outType == FType.ROW) {
			rowBeg = new long[numWorkers];
			rowEnd = new long[numWorkers];
			colBeg = new long[numWorkers];
			colEnd = new long[numWorkers];
			long base = rlen / numWorkers;
			long rem = rlen % numWorkers;
			long pos = 0;
			for (int i = 0; i < numWorkers; i++) {
				long size = base + (i < rem ? 1 : 0);
				if (size <= 0) {
					outType = FType.FULL;
					break;
				}
				rowBeg[i] = pos;
				rowEnd[i] = pos + size;
				colBeg[i] = 0;
				colEnd[i] = clen;
				pos += size;
			}
		}
		else if (outType == FType.COL) {
			rowBeg = new long[numWorkers];
			rowEnd = new long[numWorkers];
			colBeg = new long[numWorkers];
			colEnd = new long[numWorkers];
			long base = clen / numWorkers;
			long rem = clen % numWorkers;
			long pos = 0;
			for (int i = 0; i < numWorkers; i++) {
				long size = base + (i < rem ? 1 : 0);
				if (size <= 0) {
					outType = FType.FULL;
					break;
				}
				rowBeg[i] = 0;
				rowEnd[i] = rlen;
				colBeg[i] = pos;
				colEnd[i] = pos + size;
				pos += size;
			}
		}

		long outId;
		List<Pair<FederatedRange, FederatedData>> outMap = new ArrayList<>();
		if (outType == FType.FULL) {
			FederatedRequest fr = anchorMap.broadcast(in);
			anchorMap.execute(tid, true, fr);
			outId = fr.getID();
			for (Pair<FederatedRange, FederatedData> entry : anchorMap.getMap()) {
				FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {rlen, clen});
				FederatedData data = entry.getValue().copyWithNewID(outId);
				outMap.add(new ImmutablePair<>(range, data));
			}
		}
		else {
			int[][] ix = new int[numWorkers][4];
			for (int i = 0; i < numWorkers; i++) {
				long rb = rowBeg[i];
				long re = rowEnd[i];
				long cb = colBeg[i];
				long ce = colEnd[i];
				ix[i][0] = (int) rb;
				ix[i][1] = (int) (re - 1);
				ix[i][2] = (int) cb;
				ix[i][3] = (int) (ce - 1);
			}

			FederationMap sliceMap = anchorMap;
			if (anchorMap.getType() == FType.FULL)
				sliceMap = new FederationMap(anchorMap.getID(), anchorMap.getMap(), outType);
			FederatedRequest[] frSlices = sliceMap.broadcastSliced(in, false, ix);
			if (frSlices.length != numWorkers)
				throw new DMLRuntimeException("fed_fout slice request count mismatch: expected=" + numWorkers
					+ " actual=" + frSlices.length);
			anchorMap.execute(tid, true, frSlices, new FederatedRequest[0]);
			outId = frSlices[0].getID();

			for (int i = 0; i < numWorkers; i++) {
				FederatedRange range = new FederatedRange(new long[] {rowBeg[i], colBeg[i]},
					new long[] {rowEnd[i], colEnd[i]});
				FederatedData data = anchorMap.getMap().get(i).getValue().copyWithNewID(outId);
				outMap.add(new ImmutablePair<>(range, data));
			}
		}

		out.setFedMapping(new FederationMap(outId, outMap, outType));
		out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
	}
}

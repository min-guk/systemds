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

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

public class FEDFoutInstruction extends FEDInstruction {
	private final CPOperand _input;
	private final CPOperand _anchor;
	private final CPOperand _output;
	private final FType _fTypeHint;

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
			default:
				throw new DMLRuntimeException("fed_fout expects ftype literal ROW|COL|FULL but found " + fTypeLiteral);
		}
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		MatrixObject in = ec.getMatrixObject(_input);
		if (in.isFederated())
			throw new DMLRuntimeException("fed_fout expects a local input but found federated input: " + _input.getName());

		MatrixObject anchor = ec.getMatrixObject(_anchor);
		if (!anchor.isFederated())
			throw new DMLRuntimeException("fed_fout requires a federated anchor: " + _anchor.getName());

		FederationMap anchorMap = anchor.getFedMapping();
		int numWorkers = anchorMap.getSize();
		if (numWorkers <= 0)
			throw new DMLRuntimeException("fed_fout cannot materialize: no federated parent/worker pool (empty anchor map)");
		FType anchorType = anchorMap.getType();
		if (anchorType == FType.PART || anchorType == FType.OTHER)
			throw new DMLRuntimeException("fed_fout does not support anchor type " + anchorType);

		long rlen = in.getNumRows();
		long clen = in.getNumColumns();
		if (rlen < 0 || clen < 0) {
			MatrixBlock block = in.acquireRead();
			rlen = block.getNumRows();
			clen = block.getNumColumns();
			in.release();
		}
		if (rlen < 0 || clen < 0)
			throw new DMLRuntimeException("fed_fout requires known output dimensions: rlen=" + rlen + " clen=" + clen);

		FType outType = _fTypeHint != null ? _fTypeHint : FType.FULL;
		if (outType == FType.PART || outType == FType.OTHER)
			throw new DMLRuntimeException("fed_fout does not support ftype " + outType);

		long[] rowBeg = null;
		long[] rowEnd = null;
		long[] colBeg = null;
		long[] colEnd = null;
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
			anchorMap.execute(getTID(), true, fr);
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
			anchorMap.execute(getTID(), true, frSlices, new FederatedRequest[0]);
			outId = frSlices[0].getID();

			outMap.clear();
			for (int i = 0; i < numWorkers; i++) {
				FederatedRange range = new FederatedRange(new long[] {rowBeg[i], colBeg[i]},
					new long[] {rowEnd[i], colEnd[i]});
				FederatedData data = anchorMap.getMap().get(i).getValue().copyWithNewID(outId);
				outMap.add(new ImmutablePair<>(range, data));
			}
		}

		MatrixObject out = ec.getMatrixObject(_output);
		out.setFedMapping(new FederationMap(outId, outMap, outType));
		out.getDataCharacteristics().set(rlen, clen, in.getBlocksize(), in.getNnz());
	}
}

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.IndexingCPInstruction;
import org.apache.sysds.runtime.instructions.cp.ScalarObject;
import org.apache.sysds.runtime.instructions.cp.ScalarObjectFactory;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysds.runtime.instructions.spark.IndexingSPInstruction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.util.IndexRange;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

public final class IndexingFEDInstruction extends UnaryFEDInstruction {
	protected final CPOperand rowLower, rowUpper, colLower, colUpper;

	protected IndexingFEDInstruction(CPOperand in, CPOperand rl, CPOperand ru, CPOperand cl, CPOperand cu,
		CPOperand out, String opcode, String istr) {
		this(in, rl, ru, cl, cu, out, opcode, istr, FederatedOutput.NONE);
	}

	protected IndexingFEDInstruction(CPOperand in, CPOperand rl, CPOperand ru, CPOperand cl, CPOperand cu,
		CPOperand out, String opcode, String istr, FederatedOutput fedOut) {
		super(FEDInstruction.FEDType.MatrixIndexing, null, in, out, opcode, istr, fedOut);
		rowLower = rl;
		rowUpper = ru;
		colLower = cl;
		colUpper = cu;
	}

	protected IndexingFEDInstruction(CPOperand lhsInput, CPOperand rhsInput, CPOperand rl, CPOperand ru, CPOperand cl,
		CPOperand cu, CPOperand out, String opcode, String istr) {
		this(lhsInput, rhsInput, rl, ru, cl, cu, out, opcode, istr, FederatedOutput.NONE);
	}

	protected IndexingFEDInstruction(CPOperand lhsInput, CPOperand rhsInput, CPOperand rl, CPOperand ru, CPOperand cl,
		CPOperand cu, CPOperand out, String opcode, String istr, FederatedOutput fedOut) {
		super(FEDInstruction.FEDType.MatrixIndexing, null, lhsInput, rhsInput, out, opcode, istr, fedOut);
		rowLower = rl;
		rowUpper = ru;
		colLower = cl;
		colUpper = cu;
	}

	protected IndexRange getIndexRange(ExecutionContext ec) {
		// NOTE: For federated instructions, scalar operands might be patched via
		// Instruction.updateLabels (¶_VarX¶ -> "2") while retaining isLiteral=false
		// (as encoded by Lop.prepScalarOperand for non-CP exec types). In such cases,
		// ExecutionContext.getScalarInput would try to resolve a variable named "2".
		// Hence, we add a robustness fallback to interpret numeric strings as literals.
		return new IndexRange( // rl, ru, cl, ru
			(int) (getScalarIndexValue(ec, rowLower) - 1),
			(int) (getScalarIndexValue(ec, rowUpper) - 1),
			(int) (getScalarIndexValue(ec, colLower) - 1),
			(int) (getScalarIndexValue(ec, colUpper) - 1));
	}

	private static long getScalarIndexValue(ExecutionContext ec, CPOperand operand) {
		try {
			return ec.getScalarInput(operand).getLongValue();
		}
		catch(DMLRuntimeException ex) {
			String name = operand.getName();
			if(operand.isScalar() && !operand.isLiteral() && isNumericLiteral(name) && !ec.containsVariable(name))
				return ec.getScalarInput(name, operand.getValueType(), true).getLongValue();
			throw ex;
		}
	}

	private static boolean isNumericLiteral(String value) {
		if(value == null || value.isEmpty())
			return false;
		char c0 = value.charAt(0);
		if(!Character.isDigit(c0) && c0 != '-' && c0 != '+')
			return false;
		try {
			Double.parseDouble(value);
			return true;
		}
		catch(NumberFormatException e) {
			return false;
		}
	}

	public static IndexingFEDInstruction parseInstruction(IndexingCPInstruction instr) {
		return new IndexingFEDInstruction(instr.input1, instr.input2, instr.getRowLower(), instr.getRowUpper(),
			instr.getColLower(), instr.getColUpper(), instr.output, instr.getOpcode(), instr.getInstructionString());
	}

	public static IndexingFEDInstruction parseInstruction(IndexingSPInstruction instr) {
		return new IndexingFEDInstruction(instr.input1, instr.input2, instr.getRowLower(), instr.getRowUpper(),
			instr.getColLower(), instr.getColUpper(), instr.output, instr.getOpcode(), instr.getInstructionString());
	}

	public static IndexingFEDInstruction parseInstruction(String str) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];

		if(opcode.equalsIgnoreCase(Opcodes.RIGHT_INDEX.toString())) {
			if(parts.length == 7 || parts.length == 8) {
				CPOperand in, rl, ru, cl, cu, out;
				in = new CPOperand(parts[1]);
				rl = new CPOperand(parts[2]);
				ru = new CPOperand(parts[3]);
				cl = new CPOperand(parts[4]);
				cu = new CPOperand(parts[5]);
				out = new CPOperand(parts[6]);
				FederatedOutput fedOut = parts.length > 7 ? FederatedOutput.valueOf(parts[7]) : FederatedOutput.NONE;

				if(in.getDataType() != Types.DataType.MATRIX && in.getDataType() != Types.DataType.FRAME)
					throw new DMLRuntimeException("Can index only on matrices, frames in federated.");

				return new IndexingFEDInstruction(in, rl, ru, cl, cu, out, opcode, str, fedOut);
			}
			else {
				throw new DMLRuntimeException("Invalid number of operands in instruction: " + str);
			}
		}
		else if(opcode.equalsIgnoreCase(Opcodes.LEFT_INDEX.toString()) || opcode.equalsIgnoreCase("mapLeftIndex")) {
			if ( parts.length == 8 || parts.length == 9) {
				CPOperand lhsInput, rhsInput, rl, ru, cl, cu, out;
				lhsInput = new CPOperand(parts[1]);
				rhsInput = new CPOperand(parts[2]);
				rl = new CPOperand(parts[3]);
				ru = new CPOperand(parts[4]);
				cl = new CPOperand(parts[5]);
				cu = new CPOperand(parts[6]);
				out = new CPOperand(parts[7]);
				FederatedOutput fedOut = parts.length > 8 ? FederatedOutput.valueOf(parts[8]) : FederatedOutput.NONE;

				if((lhsInput.getDataType() != Types.DataType.MATRIX && lhsInput.getDataType() != Types.DataType.FRAME) &&
					(rhsInput.getDataType() != Types.DataType.MATRIX && rhsInput.getDataType() != Types.DataType.FRAME))
					throw new DMLRuntimeException("Can index only on matrices, frames, and lists.");

				return new IndexingFEDInstruction(lhsInput, rhsInput, rl, ru, cl, cu, out, opcode, str, fedOut);
			}
			else {
				throw new DMLRuntimeException("Invalid number of operands in instruction: " + str);
			}
		}
		else {
			throw new DMLRuntimeException("Unknown opcode while parsing a MatrixIndexingFEDInstruction: " + str);
		}
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		if(!input1.isList()) {
			CacheableData<?> in = ec.getCacheableData(input1);
			if(in.getFedMapping() == null)
				throw new DMLRuntimeException("FED indexing requires federated input but found local at runtime. "
					+ "op=" + instOpcode + " input=" + input1.getName() + " output=" + output.getName()
					+ " inst=" + instString);
		}

		if(getOpcode().equalsIgnoreCase(Opcodes.RIGHT_INDEX.toString()))
			rightIndexing(ec);
		else
			leftIndexing(ec);
	}


	private static String createCPOperandString(ExecutionContext ec, CPOperand operand) {
		if(operand.isLiteral())
			return InstructionUtils.createLiteralOperand(operand.getName(), operand.getValueType());

		String name = operand.getName();
		if(isNumericLiteral(name) && !ec.containsVariable(name))
			return InstructionUtils.createLiteralOperand(name, operand.getValueType());

		return InstructionUtils.concatOperandParts(
			name, operand.getDataType().name(), operand.getValueType().name(), String.valueOf(false));
	}

	private void rightIndexing(ExecutionContext ec)
	{
		//get input and requested index range
		CacheableData<?> in = ec.getCacheableData(input1);
		IndexRange ixrange = getIndexRange(ec);

		//prepare output federation map (copy-on-write)
		FederationMap fedMap = in.getFedMapping().filter(ixrange);

		//modify federated ranges in place
		String[] instStrings = new String[fedMap.getSize()];

		//create new frame schema
		List<Types.ValueType> schema = new ArrayList<>();
		// replace old reshape values for each worker
		int i = 0;
		for(Pair<FederatedRange, FederatedData> e : fedMap.getMap()) {
			FederatedRange range = e.getKey();
			long rs = range.getBeginDims()[0], re = range.getEndDims()[0],
				cs = range.getBeginDims()[1], ce = range.getEndDims()[1];
			long rsn = (ixrange.rowStart >= rs) ? (ixrange.rowStart - rs) : 0;
			long ren = (ixrange.rowEnd >= rs && ixrange.rowEnd < re) ? (ixrange.rowEnd - rs) : (re - rs - 1);
			long csn = (ixrange.colStart >= cs) ? (ixrange.colStart - cs) : 0;
			long cen = (ixrange.colEnd >= cs && ixrange.colEnd < ce) ? (ixrange.colEnd - cs) : (ce - cs - 1);

			range.setBeginDim(0, Math.max(rs - ixrange.rowStart, 0));
			range.setBeginDim(1, Math.max(cs - ixrange.colStart, 0));
			range.setEndDim(0, (ixrange.rowEnd >= re ? re-ixrange.rowStart : ixrange.rowEnd-ixrange.rowStart + 1));
			range.setEndDim(1, (ixrange.colEnd >= ce ? ce-ixrange.colStart : ixrange.colEnd-ixrange.colStart + 1));

			long[] newIx = new long[]{rsn, ren, csn, cen};

			// change 4 indices in instString
			instStrings[i] = modifyIndices(newIx, 3, 7);
			
			if(input1.isFrame()) {
				//modify frame schema
				if(in.isFederated(FType.ROW))
					schema = Arrays.asList(((FrameObject) in).getSchema((int) csn, (int) cen));
				else
					Collections.addAll(schema, ((FrameObject) in).getSchema((int) csn, (int) cen));
			}
			i++;
		}

		long id = FederationUtils.getNextFedDataID();
		Types.ExecType execType = InstructionUtils.getExecType(instString);
		if (execType == Types.ExecType.FED)
			execType = Types.ExecType.CP;
		FederatedRequest[] fr1 = FederationUtils.callInstruction(instStrings, output, id,
			new CPOperand[] {input1}, new long[] {fedMap.getID()}, execType);
		Future<FederatedResponse>[] ret = fedMap.execute(getTID(), true, fr1, new FederatedRequest[0]);

		// Scalar rightIndex: fetch the (potentially 1x1) result and return a scalar value in CP.
		// This is required for patterns like A[i,j] or A[i,] with num_runs=1 that are optimized to scalar outputs.
		if (output.isScalar()) {
			// Ensure indexing completed (and surface any worker-side errors) before fetching outputs.
			FederationUtils.sumNonZeros(ret);

			long outId = fr1[0].getID();
			FederatedRequest frG = new FederatedRequest(FederatedRequest.RequestType.GET_VAR, outId);
			FederatedRequest frC = fedMap.cleanup(getTID(), outId);
			Future<FederatedResponse>[] ffrGet = fedMap.execute(getTID(), frG, frC);

			Object scalarObj = null;
			try {
				for (Future<FederatedResponse> fr : ffrGet) {
					Object[] data = fr.get().getData();
					if (data != null && data.length > 0 && data[0] != null) {
						scalarObj = data[0];
						break;
					}
				}
			}
			catch (Exception ex) {
				throw new DMLRuntimeException(ex);
			}
			if (scalarObj == null)
				throw new DMLRuntimeException("FED rightIndex failed to retrieve scalar output from federated workers.");

			final ScalarObject outScalar;
			if (scalarObj instanceof ScalarObject)
				outScalar = ScalarObjectFactory.createScalarObject(output.getValueType(), (ScalarObject) scalarObj);
			else if (scalarObj instanceof MatrixBlock)
				outScalar = ScalarObjectFactory.createScalarObject(output.getValueType(), ((MatrixBlock) scalarObj).get(0, 0));
			else
				throw new DMLRuntimeException("FED rightIndex returned unsupported scalar output type: "
					+ scalarObj.getClass().getName());

			ec.setScalarOutput(output.getName(), outScalar);
			return;
		}

		// Respect forced local output: retrieve partition results and bind them into a local MatrixBlock.
		if (_fedOut != null && _fedOut.isForcedLocal()) {
			if (!input1.isMatrix())
				throw new DMLRuntimeException("FED rightIndex forced local output is supported only for matrices.");

			// Ensure indexing completed (and surface any worker-side errors) before fetching outputs.
			FederationUtils.sumNonZeros(ret);

			long outId = fr1[0].getID();
			FederatedRequest frG = new FederatedRequest(FederatedRequest.RequestType.GET_VAR, outId);
			FederatedRequest frC = fedMap.cleanup(getTID(), outId);
			Future<FederatedResponse>[] ffrGet = fedMap.execute(getTID(), frG, frC);

			org.apache.sysds.runtime.matrix.data.MatrixBlock mb;
			if (fedMap.getType() == FType.BROADCAST)
				mb = FederationUtils.getResults(ffrGet)[0];
			else
				mb = FederationUtils.bind(ffrGet, fedMap.getType() == FType.COL);

			ec.setMatrixOutput(output.getName(), mb);
			return;
		}
		
		//set output characteristics for frames and matrices
		CacheableData<?> out = ec.getCacheableData(output);
		if(input1.isFrame())
			((FrameObject) out).setSchema(schema.toArray(new Types.ValueType[0]));
		out.getDataCharacteristics()
			.setDimension(fedMap.getMaxIndexInRange(0), fedMap.getMaxIndexInRange(1))
			.setBlocksize(in.getBlocksize())
			.setNonZeros(FederationUtils.sumNonZeros(ret));
		out.setFedMapping(fedMap.copyWithNewID(fr1[0].getID()));
	}

	private void leftIndexing(ExecutionContext ec)
	{
		//get input and requested index range
		CacheableData<?> in1 = ec.getCacheableData(input1);
		CacheableData<?> in2 = null; // either in2 or scalar is set
		ScalarObject scalar = null;
		IndexRange ixrange = getIndexRange(ec);

		//check bounds
		if( ixrange.rowStart < 0 || ixrange.rowStart >= in1.getNumRows() || ixrange.rowEnd >= in1.getNumRows()
			|| ixrange.colStart < 0 || ixrange.colStart >= in1.getNumColumns() || ixrange.colEnd >= in1.getNumColumns() ) {
			throw new DMLRuntimeException("Invalid values for matrix indexing: ["+(ixrange.rowStart+1)+":"+(ixrange.rowEnd+1)+","
				+ (ixrange.colStart+1)+":"+(ixrange.colEnd+1)+"] " + "must be within matrix dimensions ["+in1.getNumRows()+","+in1.getNumColumns()+"].");
		}

		if(input2.getDataType() == DataType.SCALAR) {
			if(!ixrange.isScalar())
				throw new DMLRuntimeException("Invalid index range for leftindexing with scalar: " + ixrange.toString() + ".");

			scalar = ec.getScalarInput(input2);
		}
		else {
			in2 = ec.getCacheableData(input2);
			if( (ixrange.rowEnd-ixrange.rowStart+1) != in2.getNumRows() || (ixrange.colEnd-ixrange.colStart+1) != in2.getNumColumns()) {
				throw new DMLRuntimeException("Invalid values for matrix indexing: " +
					"dimensions of the source matrix ["+in2.getNumRows()+"x" + in2.getNumColumns() + "] " +
					"do not match the shape of the matrix specified by indices [" +
					(ixrange.rowStart+1) +":" + (ixrange.rowEnd+1) + ", " + (ixrange.colStart+1) + ":" + (ixrange.colEnd+1) + "].");
			}
		}

		FederationMap fedMap = in1.getFedMapping();

		String[] instStrings = new String[fedMap.getSize()];
		int[][] sliceIxs = new int[fedMap.getSize()][];
		FederatedRange[] ranges = new FederatedRange[fedMap.getSize()];

		// instruction string for copying a partition at the federated site
		int cpVarInstIx = fedMap.getSize();
		String cpVarInstString = createCopyInstString();

		// replace old reshape values for each worker
		int i = 0, prev = 0, from = fedMap.getSize();
		for(Pair<FederatedRange, FederatedData> e : fedMap.getMap()) {
			FederatedRange range = e.getKey();
			long rs = range.getBeginDims()[0], re = range.getEndDims()[0],
				cs = range.getBeginDims()[1], ce = range.getEndDims()[1];
			long rsn = (ixrange.rowStart >= rs) ? (ixrange.rowStart - rs) : 0;
			long ren = (ixrange.rowEnd >= rs && ixrange.rowEnd < re) ? (ixrange.rowEnd - rs) : (re - rs - 1);
			long csn = (ixrange.colStart >= cs) ? (ixrange.colStart - cs) : 0;
			long cen = (ixrange.colEnd >= cs && ixrange.colEnd < ce) ? (ixrange.colEnd - cs) : (ce - cs - 1);

			long[] newIx = new long[]{(int) rsn, (int) ren, (int) csn, (int) cen};

			if(in2 != null) { // matrix, frame
				// find ranges where to apply leftIndex
				long to;
				if(in1.isFederated(FType.ROW) && (to = (prev + ren - rsn)) >= 0 &&
					to < in2.getNumRows() && ixrange.rowStart <= re) {
					sliceIxs[i] = new int[] { prev, (int) to, 0, (int) in2.getNumColumns()-1};
					prev = (int) (to + 1);

					instStrings[i] = modifyIndices(newIx, 4, 8);
					ranges[i] = range;
					from = Math.min(i, from);
				}
				else if(in1.isFederated(FType.COL) && (to = (prev + cen - csn)) >= 0 &&
					to < in2.getNumColumns() && ixrange.colStart <= ce) {
					sliceIxs[i] = new int[] {0, (int) in2.getNumRows() - 1, prev, (int) to};
					prev = (int) (to + 1);

					instStrings[i] = modifyIndices(newIx, 4, 8);
					ranges[i] = range;
					from = Math.min(i, from);
				}
				else {
					// TODO shallow copy, add more advanced update in place for federated
					cpVarInstIx = Math.min(i, cpVarInstIx);
					instStrings[i] = cpVarInstString;
				}
			}
			else { // scalar
				if(ixrange.rowStart >= rs && ixrange.rowEnd < re
					&& ixrange.colStart >= cs && ixrange.colEnd < ce) {
					instStrings[i] = modifyIndices(newIx, 4, 8);
					instStrings[i] = changeScalarLiteralFlag(instStrings[i], 3);
					ranges[i] = range;
					from = Math.min(i, from);
				}
				else {
					cpVarInstIx = Math.min(i, cpVarInstIx);
					instStrings[i] = cpVarInstString;
				}
			}

			i++;
		}

		sliceIxs = Arrays.stream(sliceIxs).filter(Objects::nonNull).toArray(int[][] :: new);

		long id = FederationUtils.getNextFedDataID();
		if(in2 != null) { // matrix, frame
			FederatedRequest[] fr1 = fedMap.broadcastSliced(in2,
				DMLScript.LINEAGE ? ec.getLineageItem(input2) : null, input2.isFrame(), sliceIxs);
			FederatedRequest[] fr2 = FederationUtils.callInstruction(instStrings, output, id, new CPOperand[]{input1, input2},
				new long[]{fedMap.getID(), fr1[0].getID()}, null);
			FederatedRequest fr3 = fedMap.cleanup(getTID(), fr1[0].getID());

			//execute federated instruction and cleanup intermediates
			if(sliceIxs.length == fedMap.getSize())
				fedMap.execute(getTID(), true, fr2, fr1, fr3);
			else
				fedMap.execute(getTID(), true, ranges, fr2[cpVarInstIx], Arrays.copyOfRange(fr2, from, from + sliceIxs.length), fr1, fr3);
		}
		else { // scalar
			FederatedRequest fr1 = fedMap.broadcast(scalar);
			FederatedRequest[] fr2 = FederationUtils.callInstruction(instStrings, output, id, new CPOperand[]{input1, input2},
				new long[]{fedMap.getID(), fr1.getID()}, null);
			FederatedRequest fr3 = fedMap.cleanup(getTID(), fr1.getID());

			if(fr2.length == 1)
				fedMap.execute(getTID(), true, fr1, fr2[0], fr3);
			else
				fedMap.execute(getTID(), true, ranges, fr2[cpVarInstIx], fr2[from], fr1, fr3);
		}

		if(input1.isFrame()) {
			FrameObject out = ec.getFrameObject(output);
			out.setSchema(((FrameObject) in1).getSchema());
			out.getDataCharacteristics().set(in1.getDataCharacteristics());
			out.setFedMapping(fedMap.copyWithNewID(id));
		} else {
			MatrixObject out = ec.getMatrixObject(output);
			out.getDataCharacteristics().set(in1.getDataCharacteristics());
			out.setFedMapping(fedMap.copyWithNewID(id));
		}
	}

	private String modifyIndices(long[] newIx, int from, int to) {
		// change 4 indices in instString
		String[] instParts = instString.split(Lop.OPERAND_DELIMITOR);
		for(int j = from; j < to; j++)
			instParts[j] = InstructionUtils.createLiteralOperand(String.valueOf(newIx[j-from]+1), ValueType.INT64);
		return String.join(Lop.OPERAND_DELIMITOR, instParts);
	}

	private String changeScalarLiteralFlag(String inst, int partIx) {
		// change the literal flag of the broadcast scalar
		String[] instParts = inst.split(Lop.OPERAND_DELIMITOR);
		instParts[partIx] = instParts[partIx].replace("true", "false");
		return String.join(Lop.OPERAND_DELIMITOR, instParts);
	}

	private String createCopyInstString() {
		String[] instParts = instString.split(Lop.OPERAND_DELIMITOR);
		return VariableCPInstruction.prepareCopyInstruction(instParts[2], instParts[8]).toString();
	}
}

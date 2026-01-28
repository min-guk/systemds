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

import java.util.concurrent.Future;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.AggregateUnaryCPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.ScalarObject;
import org.apache.sysds.runtime.instructions.spark.AggregateUnarySPInstruction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.AggregateUnaryOperator;
import org.apache.sysds.runtime.matrix.operators.Operator;
import org.apache.sysds.runtime.meta.DataCharacteristics;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;

public class AggregateUnaryFEDInstruction extends UnaryFEDInstruction {
	private static final boolean DEBUG_KMEANS = Boolean.getBoolean("sysds.debug.kmeans");
	private static boolean isDebugKMeansVar(String name) {
		return "minD".equals(name) || "P_denom".equals(name);
	}

	private AggregateUnaryFEDInstruction(AggregateUnaryOperator auop,
		CPOperand in, CPOperand out, String opcode, String istr, FederatedOutput fedOut)
	{
		super(FEDType.AggregateUnary, auop, in, out, opcode, istr, fedOut);
	}

	protected AggregateUnaryFEDInstruction(Operator op,
		CPOperand in1, CPOperand in2, CPOperand out, String opcode, String istr, FederatedOutput fedOut)
	{
		super(FEDType.AggregateUnary, op, in1, in2, out, opcode, istr, fedOut);
	}

	protected AggregateUnaryFEDInstruction(Operator op,
		CPOperand in1, CPOperand in2, CPOperand out, String opcode, String istr)
	{
		super(FEDType.AggregateUnary, op, in1, in2, out, opcode, istr);
	}

	protected AggregateUnaryFEDInstruction(Operator op, CPOperand in1,
		CPOperand in2, CPOperand in3, CPOperand out, String opcode, String istr)
	{
		super(FEDType.AggregateUnary, op, in1, in2, in3, out, opcode, istr);
	}

	public static AggregateUnaryFEDInstruction parseInstruction(AggregateUnaryCPInstruction instr) {
		return new AggregateUnaryFEDInstruction(instr.getOperator(), instr.input1, instr.input2, instr.input3,
			instr.output, instr.getOpcode(), instr.getInstructionString());
	}

	public static AggregateUnaryFEDInstruction parseInstruction(AggregateUnarySPInstruction instr) {
		// TODO: during processing the NONE-flag of AggregateUnarySPInstruction (SparkAggType) will be removed, making the instruction unparseable
		return new AggregateUnaryFEDInstruction(instr.getOperator(), instr.input1, instr.input2, instr.input3,
			instr.output, instr.getOpcode(), instr.getInstructionString());
	}
	
	public static AggregateUnaryFEDInstruction parseInstruction(String str) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand out = new CPOperand(parts[2]);

		AggregateUnaryOperator aggun = null;
		if(opcode.equalsIgnoreCase("uarimax") || opcode.equalsIgnoreCase("uarimin"))
			if(InstructionUtils.getExecType(str) == ExecType.SPARK)
				aggun = InstructionUtils.parseAggregateUnaryRowIndexOperator(opcode, 1, 1);
			else
				aggun = InstructionUtils.parseAggregateUnaryRowIndexOperator(opcode, Integer.parseInt(parts[4]), 1);
		else
			aggun = InstructionUtils.parseBasicAggregateUnaryOperator(opcode);

		FederatedOutput fedOut = null;
		if ( parts.length == 5 && !parts[4].equals("uarimin") && !parts[4].equals("uarimax") )
			fedOut = FederatedOutput.valueOf(parts[4]);
		else
			fedOut = FederatedOutput.valueOf(parts[5]);
		return new AggregateUnaryFEDInstruction(aggun, in1, out, opcode, str, fedOut);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		if (getOpcode().contains("var")) {
			processVar(ec);
		} else {
			processDefault(ec);
		}
	}

	private void processDefault(ExecutionContext ec){
		AggregateUnaryOperator aop = (AggregateUnaryOperator) _optr;
		MatrixObject in = ec.getMatrixObject(input1);
		if ( !in.isFederated() ) {
			throw new DMLRuntimeException("FED aggregate unary requires federated input but found local at runtime. "
				+ "op=" + instOpcode + " input=" + input1.getName()
				+ " dims=" + in.getNumRows() + "x" + in.getNumColumns()
				+ " fedOut=" + _fedOut + " inst=" + instString);
		}
		FederationMap map = in.getFedMapping();
		if ( map == null )
			throw new DMLRuntimeException("Input federation map is null for input " + input1);

		if((instOpcode.equalsIgnoreCase("uarimax") || instOpcode.equalsIgnoreCase("uarimin")) && in.isFederated(FType.COL))
			instString = InstructionUtils.replaceOperand(instString, 5, "2");

		// create federated commands for aggregation
		// (by default obtain output, even though unnecessary row aggregates)
		if ( _fedOut.isForcedFederated() )
			if(instString.startsWith("SPARK"))
				processFederatedSPOutput(map, in, ec, aop);
			else
				processFederatedOutput(map, in, ec);
		else {
			if(instString.startsWith("SPARK"))
				processGetSPOutput(map, in, ec, aop);
			else
				processGetOutput(map, aop, ec, in);
		}
	}

	/**
	 * Sends federated request with instruction without retrieving the result from the workers.
	 * @param map federation map of the input
	 * @param in input matrix object
	 * @param ec execution context
	 */
	private void processFederatedOutput(FederationMap map, MatrixObject in, ExecutionContext ec){
		if ( output.isScalar() )
			throw new DMLRuntimeException("Output of FED instruction, " + output.toString()
					+ ", is a scalar and the output is set to be federated. Scalars cannot be federated. ");
		AggregateUnaryOperator aop = (AggregateUnaryOperator) _optr;
		FType inFtype = in.getFedMapping().getType();
		boolean isColAgg = aop.isColAggregate();
		// Only true ROW/COL partitioning requires global consolidation. Treat BROADCAST/FULL separately.
		boolean needsGlobalAggregation = (inFtype == FType.ROW && isColAgg) ||
			(inFtype == FType.COL && !isColAgg);

		// If the output would be PART (requires consolidation across workers), we cannot keep it as-is on the workers.
		// Instead, aggregate the partial results locally and broadcast the consolidated vector/matrix back to all workers.
		if( needsGlobalAggregation ) {
			FederatedRequest fr1 = FederationUtils.callInstruction(instString, output,
				new CPOperand[]{input1}, new long[]{in.getFedMapping().getID()}, true);
			FederatedRequest fr2 = new FederatedRequest(RequestType.GET_VAR, fr1.getID());
			Future<FederatedResponse>[] tmp = map.execute(getTID(), true, fr1, fr2);
			MatrixBlock agg = FederationUtils.aggMatrix(aop, tmp, map);

			long broadcastId = FederationUtils.getNextFedDataID();
			FederatedRequest fr3 = new FederatedRequest(RequestType.PUT_VAR, broadcastId, agg);
			FederatedRequest fr4 = map.cleanup(getTID(), fr1.getID());
			map.execute(getTID(), true, fr3, fr4);

			MatrixObject out = ec.getMatrixObject(output);
			out.getDataCharacteristics()
				.setDimension(agg.getNumRows(), agg.getNumColumns())
				.setBlocksize(in.getBlocksize())
				.setNonZeros(agg.getNonZeros());
			FederationMap outFedMap = in.getFedMapping()
				.copyWithNewIDAndRange(agg.getNumRows(), agg.getNumColumns(), broadcastId, FType.BROADCAST);
			out.setFedMapping(outFedMap);
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] aggUnary " + instOpcode + " out=" + output.getName()
					+ " dims=" + agg.getNumRows() + "x" + agg.getNumColumns()
					+ " ftype=BROADCAST");
			}
			return;
		}

		FederatedRequest fr1 = FederationUtils.callInstruction(instString, output,
			new CPOperand[]{input1}, new long[]{in.getFedMapping().getID()}, true);
		map.execute(getTID(), true, fr1);

		MatrixObject out = ec.getMatrixObject(output);
		deriveNewOutputFedMapping(in, out, fr1);
	}

	/**
	 * Set output fed mapping based on federated partitioning and aggregation type.
	 * @param in matrix object from which fed partitioning originates from
	 * @param out matrix object holding the dimensions of the instruction output
	 * @param fr1 federated request holding the instruction execution call
	 */
	private void deriveNewOutputFedMapping(MatrixObject in, MatrixObject out, FederatedRequest fr1){
		//Get agg type
		//if ( !(instOpcode.equals("uack+") || instOpcode.equals("uark+")) )
		//	throw new DMLRuntimeException("Operation " + instOpcode + " is unknown to FOUT processing");
		boolean isColAgg = ((AggregateUnaryOperator) _optr).isColAggregate();
		//Get partition type
		FType inFtype = in.getFedMapping().getType();
		// Preserve replication semantics for replicated inputs.
		if (inFtype == FType.BROADCAST || inFtype == FType.FULL) {
			out.setFedMapping(in.getFedMapping().copyWithNewIDAndRange(
				out.getNumRows(), out.getNumColumns(), fr1.getID(), inFtype));
			return;
		}

		//Get fedmap from in
		FederationMap inputFedMapCopy = in.getFedMapping().copyWithNewID(fr1.getID());

		//if partition type is row and aggregation type is row
		//   then get row dim split from input and use as row dimension and get col dimension from output col dimension
		//   and set FType to ROW
		if ( inFtype == FType.ROW && !isColAgg ){
			for ( FederatedRange range : inputFedMapCopy.getFederatedRanges() )
				range.setEndDim(1,out.getNumColumns());
			inputFedMapCopy.setType(FType.ROW);
		}
		//if partition type is row and aggregation type is col
		//   then get row and col dimension from out and use those dimensions for both federated workers
		//   and set FType to PART
		//if partition type is col and aggregation type is row
		//   then set row and col dimension from out and use those dimensions for both federated workers
		//   and set FType to PART
		if ( (inFtype == FType.ROW && isColAgg) || (inFtype == FType.COL && !isColAgg) ){
			/*for ( FederatedRange range : inputFedMapCopy.getFederatedRanges() ){
				range.setBeginDim(0,0);
				range.setBeginDim(1,0);
				range.setEndDim(0,out.getNumRows());
				range.setEndDim(1,out.getNumColumns());
			}
			inputFedMapCopy.setType(FType.PART);*/
			throw new DMLRuntimeException("PART output not supported");
		}
		//if partition type is col and aggregation type is col
		//   then set row dimension to output and col dimension to in col split
		//   and set FType to COL
		if ( inFtype == FType.COL && isColAgg ){
			for ( FederatedRange range : inputFedMapCopy.getFederatedRanges() )
				range.setEndDim(0,out.getNumRows());
			inputFedMapCopy.setType(FType.COL);
		}

		//set out fedmap in the end
		out.setFedMapping(inputFedMapCopy);
		if (DEBUG_KMEANS) {
			System.out.println("[DBG-KMEANS] aggUnary " + instOpcode + " out=" + output.getName()
				+ " dims=" + out.getNumRows() + "x" + out.getNumColumns()
				+ " ftype=" + inputFedMapCopy.getType());
		}
	}

	/**
	 * Sends federated request with instruction and retrieves the result from the workers.
	 * @param map federation map of input
	 * @param aggUOptr aggregate unary operator of the instruction
	 * @param ec execution context
	 * @param in input matrix object
	 */
	private void processGetOutput(FederationMap map, AggregateUnaryOperator aggUOptr, ExecutionContext ec, MatrixObject in){
		FederatedRequest fr1 = FederationUtils.callInstruction(instString, output,
			new CPOperand[]{input1}, new long[]{in.getFedMapping().getID()}, true);
		FederatedRequest fr2 = new FederatedRequest(RequestType.GET_VAR, fr1.getID());

		//execute federated commands and cleanups
		Future<FederatedResponse>[] tmp = map.execute(getTID(), fr1, fr2);
		if( output.isScalar() )
			ec.setVariable(output.getName(), FederationUtils.aggScalar(aggUOptr, tmp, map));
		else
			ec.setMatrixOutput(output.getName(), FederationUtils.aggMatrix(aggUOptr, tmp, map));
	}

	private void processVar(ExecutionContext ec){
		if ( _fedOut.isForcedFederated() ){
			throw new DMLRuntimeException("Output of " + toString() + " should not be federated "
				+ "since the instruction requires consolidation of partial results to be computed.");
		}

		boolean isSpark = instString.startsWith("SPARK");

		AggregateUnaryOperator aop = (AggregateUnaryOperator) _optr;
		MatrixObject in = ec.getMatrixObject(input1);
		FederationMap map = in.getFedMapping();

		long id = FederationUtils.getNextFedDataID();
		FederatedRequest tmpRequest = null;
		if(isSpark) {
			if ( output.isScalar() ) {
				ScalarObject scalarOut = ec.getScalarInput(output);
				tmpRequest = map.broadcast(scalarOut);
				id = tmpRequest.getID();
			}
			else {
				if((map.getType() == FType.COL && aop.isColAggregate()) || (map.getType() == FType.ROW && aop.isRowAggregate()))
					tmpRequest = new FederatedRequest(RequestType.PUT_VAR, id, new MatrixCharacteristics(-1, -1), in.getDataType());
				else {
					DataCharacteristics dc = ec.getDataCharacteristics(output.getName());
					tmpRequest = new FederatedRequest(RequestType.PUT_VAR, id, dc, in.getDataType());
				}
			}
		}

		// federated ranges mean for variance
		Future<FederatedResponse>[] meanTmp = null;
		if (getOpcode().contains("var")) {
			String meanInstr = instString.replace(getOpcode(), getOpcode().replace("var", "mean"));

			//create federated commands for aggregation
			FederatedRequest meanFr1 =  FederationUtils.callInstruction(meanInstr, output, id,
				new CPOperand[]{input1}, new long[]{in.getFedMapping().getID()}, isSpark ? ExecType.SPARK : ExecType.CP, isSpark);
			FederatedRequest meanFr2 = new FederatedRequest(RequestType.GET_VAR, meanFr1.getID());
			meanTmp = map.execute(getTID(), true, isSpark ?
				new FederatedRequest[] {tmpRequest, meanFr1, meanFr2} :
				new FederatedRequest[] {meanFr1, meanFr2});
		}

		//create federated commands for aggregation
		FederatedRequest fr1 = FederationUtils.callInstruction(instString, output, id,
			new CPOperand[]{input1}, new long[]{in.getFedMapping().getID()}, isSpark ? ExecType.SPARK : ExecType.CP, isSpark);
		FederatedRequest fr2 = new FederatedRequest(RequestType.GET_VAR, fr1.getID());
		
		//execute federated commands and cleanups
		Future<FederatedResponse>[] tmp = map.execute(getTID(), true, isSpark ?
			new FederatedRequest[] {tmpRequest, fr1, fr2} :
			new FederatedRequest[] { fr1, fr2});
		if( output.isScalar() )
			ec.setVariable(output.getName(), FederationUtils.aggScalar(aop, tmp, meanTmp, map));
		else
			ec.setMatrixOutput(output.getName(), FederationUtils.aggMatrix(aop, tmp, meanTmp, map));
	}

	private void processFederatedSPOutput(FederationMap map, MatrixObject in, ExecutionContext ec, AggregateUnaryOperator aop) {
		DataCharacteristics dc = ec.getDataCharacteristics(output.getName());
		FederatedRequest fr1;
		long id = FederationUtils.getNextFedDataID();

		if((map.getType() == FType.COL && aop.isColAggregate()) ||
			(map.getType() == FType.ROW && aop.isRowAggregate()))
			fr1 = new FederatedRequest(RequestType.PUT_VAR, id, new MatrixCharacteristics(-1, -1), in.getDataType());
		else
			fr1 = new FederatedRequest(RequestType.PUT_VAR, id, dc, in.getDataType());
		FederatedRequest fr2 = FederationUtils.callInstruction(instString, output, id,
			new CPOperand[]{input1}, new long[]{in.getFedMapping().getID()}, ExecType.SPARK, true);

		map.execute(getTID(), true, fr1, fr2);
		// derive new fed mapping for output
		MatrixObject out = ec.getMatrixObject(output);
		out.setFedMapping(in.getFedMapping().copyWithNewID(fr2.getID()));
	}

	private void processGetSPOutput(FederationMap map, MatrixObject in, ExecutionContext ec, AggregateUnaryOperator aop) {
		DataCharacteristics dc = ec.getDataCharacteristics(output.getName());
		FederatedRequest fr1;
		long id = FederationUtils.getNextFedDataID();

		if ( output.isScalar() ) {
			ScalarObject scalarOut = ec.getScalarInput(output);
			fr1 = map.broadcast(scalarOut);
			id = fr1.getID();
		}
		else {
			if((map.getType() == FType.COL && aop.isColAggregate()) || (map.getType() == FType.ROW && aop.isRowAggregate()))
				fr1 = new FederatedRequest(RequestType.PUT_VAR, id, new MatrixCharacteristics(-1, -1), in.getDataType());
			else
				fr1 = new FederatedRequest(RequestType.PUT_VAR, id, dc, in.getDataType());
		}

		FederatedRequest fr2 = FederationUtils.callInstruction(instString, output, id,
			new CPOperand[]{input1}, new long[]{in.getFedMapping().getID()}, ExecType.SPARK, true);
		FederatedRequest fr3 = new FederatedRequest(RequestType.GET_VAR, fr2.getID());

		//execute federated commands and cleanups
		Future<FederatedResponse>[] tmp = map.execute(getTID(), fr1, fr2, fr3);
		if( output.isScalar() )
			ec.setVariable(output.getName(), FederationUtils.aggScalar(aop, tmp, map));
		else
			ec.setMatrixOutput(output.getName(), FederationUtils.aggMatrix(aop, tmp, map));
	}
}

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

import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.hops.fedplanner.FTypes.AlignType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.controlprogram.federated.MatrixLineagePair;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.AggregateBinaryCPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.Operator;

public class AggregateBinaryFEDInstruction extends BinaryFEDInstruction {
	private static final Log LOG = LogFactory.getLog(AggregateBinaryFEDInstruction.class.getName());
	private static final boolean DEBUG_KMEANS = Boolean.getBoolean("sysds.debug.kmeans");
	
	public AggregateBinaryFEDInstruction(Operator op, CPOperand in1,
		CPOperand in2, CPOperand out, String opcode, String istr) {
		super(FEDType.AggregateBinary, op, in1, in2, out, opcode, istr);
	}

	public AggregateBinaryFEDInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand out,
		String opcode, String istr, FederatedOutput fedOut) {
		super(FEDType.AggregateBinary, op, in1, in2, out, opcode, istr, fedOut);
	}

	public static AggregateBinaryFEDInstruction parseInstruction(AggregateBinaryCPInstruction inst,
		ExecutionContext ec) {
		if(inst.input1.isMatrix() && inst.input2.isMatrix()) {
			MatrixObject mo1 = ec.getMatrixObject(inst.input1);
			MatrixObject mo2 = ec.getMatrixObject(inst.input2);
			if((mo1.isFederated(FType.ROW) && mo1.isFederatedExcept(FType.BROADCAST)) ||
				(mo2.isFederated(FType.ROW) && mo2.isFederatedExcept(FType.BROADCAST)) ||
				(mo1.isFederated(FType.COL) && mo1.isFederatedExcept(FType.BROADCAST))) {
				return AggregateBinaryFEDInstruction.parseInstruction(inst);
			}
		}
		return null;
	}

	private static AggregateBinaryFEDInstruction parseInstruction(AggregateBinaryCPInstruction instr) {
		return new AggregateBinaryFEDInstruction(instr.getOperator(), instr.input1, instr.input2, instr.output,
			instr.getOpcode(), instr.getInstructionString(), FederatedOutput.NONE);
	}

	public static AggregateBinaryFEDInstruction parseInstruction(String str) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		if(!opcode.equalsIgnoreCase(Opcodes.MMULT.toString()))
			throw new DMLRuntimeException("AggregateBinaryInstruction.parseInstruction():: Unknown opcode " + opcode);

		InstructionUtils.checkNumFields(parts, 5);
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand in2 = new CPOperand(parts[2]);
		CPOperand out = new CPOperand(parts[3]);
		int k = Integer.parseInt(parts[4]);
		FederatedOutput fedOut = FederatedOutput.valueOf(parts[5]);
		return new AggregateBinaryFEDInstruction(
			InstructionUtils.getMatMultOperator(k), in1, in2, out, opcode, str, fedOut);
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) {
		MatrixLineagePair mo1 = ec.getMatrixLineagePair(input1);
		MatrixLineagePair mo2 = ec.getMatrixLineagePair(input2);
		mo1 = ensureRemoteFederatedInputForFedAggregate(ec, input1, mo1, mo2);
		mo2 = ensureRemoteFederatedInputForFedAggregate(ec, input2, mo2, mo1);
		if (DEBUG_KMEANS) {
			String f1 = mo1.isFederated() && mo1.getFedMapping() != null ? mo1.getFedMapping().getType().name() : "LOCAL";
			String f2 = mo2.isFederated() && mo2.getFedMapping() != null ? mo2.getFedMapping().getType().name() : "LOCAL";
			System.out.println("[DBG-KMEANS] aggBinary in1=" + input1.getName()
				+ " dims1=" + mo1.getNumRows() + "x" + mo1.getNumColumns()
				+ " ftype1=" + f1
				+ " local1=" + !mo1.isFederated()
				+ " in2=" + input2.getName()
				+ " dims2=" + mo2.getNumRows() + "x" + mo2.getNumColumns()
				+ " ftype2=" + f2
				+ " local2=" + !mo2.isFederated()
				+ " fedOut=" + _fedOut
				+ " inst=" + instString);
		}

		if (!mo1.isFederated() && !mo2.isFederated()) {
			throw new DMLRuntimeException("FED aggregate binary requires at least one federated input but both are local. "
				+ "op=" + instOpcode + " in1=" + input1.getName()
				+ " dims1=" + mo1.getNumRows() + "x" + mo1.getNumColumns()
				+ " in2=" + input2.getName()
				+ " dims2=" + mo2.getNumRows() + "x" + mo2.getNumColumns()
				+ " fedOut=" + _fedOut + " inst=" + instString);
		}

		//TODO cleanup unnecessary redundancy
		//#1 federated matrix-vector multiplication
		if(mo1.isFederated(FType.COL) && mo2.isFederated(FType.ROW)
			&& mo1.getFedMapping().isAligned(mo2.getFedMapping(), AlignType.COL_T) ) {
			FederatedRequest fr1 = FederationUtils.callInstruction(instString, output,
				new CPOperand[]{input1, input2},
				new long[]{mo1.getFedMapping().getID(), mo2.getFedMapping().getID()}, true);
			if (_fedOut.isForcedFederated() && haveCompleteCoLocatedInputs(mo1, mo2)) {
				Future<FederatedResponse>[] ffr = mo1.getFedMapping().execute(getTID(), true, fr1);
				setOutputFedMapping(mo1.getFedMapping(), mo1, mo2,
					firstNonZeros(ffr), fr1.getID(), ec);
			}
			else {
				if (_fedOut.isForcedFederated())
					writeInfoLog(mo1, mo2);
				aggregateLocally(mo1.getFedMapping(), true, ec, fr1);
			}
		}
		// SPECIAL CASE: broadcast-left x row-federated-right should slice/broadcast the left input
		// according to the RHS row partitions and aggregate by addition. Broadcasting the federated
		// RHS would attempt to materialize it locally and can yield empty blocks.
		else if (mo2.isFederated(FType.ROW) && mo1.isFederated(FType.BROADCAST)
			&& !mo2.isFederated(FType.BROADCAST)) {
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] aggBinary branch=broadcastLeft_rowRight_slice");
			}
			if (mo2.isFederated(FType.BROADCAST)) {
				// should not happen due to guard, but keep for completeness
				FederatedRequest fr1 = mo2.getFedMapping().broadcast(mo1);
				FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
					new CPOperand[]{input1, input2},
					new long[]{fr1.getID(), mo2.getFedMapping().getID()}, true);
				aggregateLocallySingleWorker(mo2.getFedMapping(), ec, fr1, fr2);
			}
			else {
				// slice left input along RHS row partitions (columns of the left input)
				FederatedRequest[] fr1 = mo2.getFedMapping().broadcastSliced(mo1, true);
				FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
					new CPOperand[]{input1, input2},
					new long[]{fr1[0].getID(), mo2.getFedMapping().getID()}, true);
				if ( _fedOut.isForcedFederated() ){
					writeInfoLog(mo1, mo2);
				}
				aggregateLocally(mo2.getFedMapping(), true, ec, fr1, fr2);
			}
		}
		// SPECIAL CASE: broadcast-left x local-right. Both operands will be identical on all workers
		// after broadcasting the local RHS, so the result is replicated. Aggregating by binding would
		// incorrectly stack identical results (e.g., W x N instead of 1 x N for kmeans centroid placer).
		else if (mo1.isFederated(FType.BROADCAST) && !mo2.isFederated()) {
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] aggBinary branch=broadcastLeft_localRight_single");
			}
			FederatedRequest fr1 = mo1.getFedMapping().broadcast(mo2);
			FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
				new CPOperand[]{input1, input2},
				new long[]{mo1.getFedMapping().getID(), fr1.getID()}, true);
			if (_fedOut.isForcedFederated()) {
				FederatedRequest frC = mo1.getFedMapping().cleanup(getTID(), fr1.getID());
				Future<FederatedResponse>[] ffr = mo1.getFedMapping().execute(getTID(), true, fr1, fr2, frC);
				setOutputFedMapping(mo1.getFedMapping(), mo1, mo2,
					FederationUtils.sumNonZeros(ffr), fr2.getID(), ec);
			}
			else {
				aggregateLocallySingleWorker(mo1.getFedMapping(), ec, fr1, fr2);
			}
		}
		else if(mo1.isFederated(FType.ROW)) { // MV + MM
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] aggBinary branch=leftRow");
			}
			//construct commands: broadcast rhs (unless already federated broadcast on same workers), fed mv, retrieve results
			FederatedRequest fr1 = null;
			long rhsID;
			boolean rhsBroadcast = mo2.isFederated(FType.BROADCAST);
			if (rhsBroadcast) {
				if (!isSameWorkerPool(mo1.getFedMapping(), mo2.getFedMapping()))
					throw new DMLRuntimeException("FED aggregate binary requires aligned worker pools for federated broadcast "
						+ "input. op=" + instOpcode + " in1=" + input1.getName() + " in2=" + input2.getName());
				rhsID = mo2.getFedMapping().getID();
			}
			else {
				fr1 = mo1.getFedMapping().broadcast(mo2);
				rhsID = fr1.getID();
			}
			FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
				new CPOperand[]{input1, input2},
				new long[]{mo1.getFedMapping().getID(), rhsID}, true);

			boolean isVector = mo2.getNumColumns() == 1;
			boolean isPartOut = mo1.isFederated(FType.PART) || // MV and MM
				(!isVector && mo2.isFederated(FType.PART)); // only MM
			if(isPartOut && _fedOut.isForcedFederated()) {
				writeInfoLog(mo1, mo2);
			}
			if((_fedOut.isForcedFederated() || (!isVector && !_fedOut.isForcedLocal()))
				&& !isPartOut) { // not creating federated output in the MV case for reasons of performance
				Future<FederatedResponse>[] ffr = null;
				if (fr1 != null) {
					FederatedRequest frC = mo1.getFedMapping().cleanup(getTID(), fr1.getID());
					ffr = mo1.getFedMapping().execute(getTID(), true, fr1, fr2, frC);
				}
				else {
					ffr = mo1.getFedMapping().execute(getTID(), true, fr2);
				}
				setOutputFedMapping(mo1.getFedMapping(), mo1, mo2,
					FederationUtils.sumNonZeros(ffr), fr2.getID(), ec);
			}
			else {
				// If the left input is replicated (BROADCAST) and the RHS is also replicated, all workers compute
				// the same result -> use one. Otherwise, BROADCAST x ROW requires summing partial results across
				// workers (the RHS is partitioned along the shared dimension).
				if (mo1.isFederated(FType.BROADCAST) && rhsBroadcast && !isPartOut) {
					if (DEBUG_KMEANS) {
						System.out.println("[DBG-KMEANS] aggBinary agg=single reason=broadcastXbroadcast");
					}
					if (fr1 != null)
						aggregateLocallySingleWorker(mo1.getFedMapping(), ec, fr1, fr2);
					else
						aggregateLocallySingleWorker(mo1.getFedMapping(), ec, fr2);
				}
				else if (mo1.isFederated(FType.BROADCAST) && mo2.isFederated(FType.ROW) && !rhsBroadcast) {
					if (DEBUG_KMEANS) {
						System.out.println("[DBG-KMEANS] aggBinary agg=add reason=broadcastXrow");
					}
					if (fr1 != null)
						aggregateLocally(mo1.getFedMapping(), true, ec, fr1, fr2);
					else
						aggregateLocally(mo1.getFedMapping(), true, ec, fr2);
				}
				else {
					if (DEBUG_KMEANS) {
						System.out.println("[DBG-KMEANS] aggBinary agg=bind reason=default");
					}
					if (fr1 != null)
						aggregateLocally(mo1.getFedMapping(), false, ec, fr1, fr2);
					else
						aggregateLocally(mo1.getFedMapping(), false, ec, fr2);
				}
			}
		}
			//#2 vector - federated matrix multiplication
			else if (mo2.isFederated(FType.ROW)) {// VM + MM
				if (DEBUG_KMEANS) {
					System.out.println("[DBG-KMEANS] aggBinary branch=rightRow");
				}
				// If the RHS is replicated (BROADCAST), every worker would compute the same result.
				// Sliced broadcast and aggregation-by-add would either fail (invalid slicing) or duplicate results.
				if (mo2.isFederated(FType.BROADCAST)) {
					FederatedRequest fr1 = mo2.getFedMapping().broadcast(mo1);
					FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
						new CPOperand[]{input1, input2},
						new long[]{fr1.getID(), mo2.getFedMapping().getID()}, true);
					if (_fedOut.isForcedFederated()) {
						FederatedRequest frC = mo2.getFedMapping().cleanup(getTID(), fr1.getID());
						Future<FederatedResponse>[] ffr = mo2.getFedMapping().execute(getTID(), true, fr1, fr2, frC);
						long nnz = -1;
						try {
							Object[] data = ffr[0].get().getData();
							if (data != null && data.length > 0 && data[0] instanceof Long)
								nnz = (Long) data[0];
						}
						catch(Exception ex) {
							throw new DMLRuntimeException(ex);
						}
						setOutputFedMapping(mo2.getFedMapping(), mo1, mo2, nnz, fr2.getID(), ec);
					}
					else {
						aggregateLocallySingleWorker(mo2.getFedMapping(), ec, fr1, fr2);
					}
					return;
				}
				//construct commands: broadcast rhs, fed mv, retrieve results
				FederatedRequest[] fr1 = mo2.getFedMapping().broadcastSliced(mo1, true);
				FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
					new CPOperand[]{input1, input2},
				new long[]{fr1[0].getID(), mo2.getFedMapping().getID()}, true);
			if ( _fedOut.isForcedFederated() ){
				writeInfoLog(mo1, mo2);
			}
			aggregateLocally(mo2.getFedMapping(), true, ec, fr1, fr2);
		}
		//#3 col-federated matrix vector multiplication
		else if (mo1.isFederated(FType.COL)) {// VM + MM
			//construct commands: broadcast rhs, fed mv, retrieve results
			FederatedRequest[] fr1 = mo1.getFedMapping().broadcastSliced(mo2, true);
			FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
				new CPOperand[]{input1, input2},
				new long[]{mo1.getFedMapping().getID(), fr1[0].getID()}, true);
			if ( _fedOut.isForcedFederated() ){
				writeInfoLog(mo1, mo2);
			}
			aggregateLocally(mo1.getFedMapping(), true, ec, fr1, fr2);
		}
		else { //other combinations
			throw new DMLRuntimeException("Federated AggregateBinary not supported with the "
				+ "following federated objects: "+mo1.isFederated()+":"+mo1.getFedMapping()
				+" "+mo2.isFederated()+":"+mo2.getFedMapping());
		}
	}

	private MatrixLineagePair ensureRemoteFederatedInputForFedAggregate(ExecutionContext ec, CPOperand operand,
			MatrixLineagePair input, MatrixLineagePair other) {
		if (input == null || !input.isFederated() || input.getFedMapping() == null
			|| !FEDLocalMaterializeUtil.hasLocalFederatedData(input.getFedMapping()))
			return input;
		FederationMap anchor = selectRemoteAnchor(other, input);
		if (anchor == null || anchor.getSize() <= 0)
			return input;
		FType anchorType = FEDLocalMaterializeUtil.normalizeSupportedAnchorType(anchor);
		if (anchorType == FType.PART || anchorType == FType.OTHER)
			return input;

		MatrixObject mo = input.getMO();
		long rlen = mo.getNumRows();
		long clen = mo.getNumColumns();
		if (rlen < 0 || clen < 0) {
			MatrixBlock block = mo.acquireRead();
			rlen = block.getNumRows();
			clen = block.getNumColumns();
			mo.release();
		}
		if (rlen < 0 || clen < 0)
			return input;

		FType requested = input.getFedMapping().getType();
		FType materializeType = (requested == FType.ROW || requested == FType.COL || requested == FType.FULL)
			? requested : anchorType;
		if (materializeType == FType.BROADCAST)
			materializeType = FType.FULL;
		if (materializeType == FType.PART || materializeType == FType.OTHER)
			materializeType = (anchorType == FType.ROW || anchorType == FType.COL) ? anchorType : FType.FULL;
		FType mapType = (requested == FType.BROADCAST) ? FType.BROADCAST : materializeType;
		if (materializeType == FType.ROW && rlen < anchor.getSize()) {
			materializeType = FType.FULL;
			mapType = FType.BROADCAST;
		}
		else if (materializeType == FType.COL && clen < anchor.getSize()) {
			materializeType = FType.FULL;
			mapType = FType.BROADCAST;
		}

		FederationMap remoteMap = FEDLocalMaterializeUtil.materializeLocalToAnchor(getTID(), mo, anchor,
			materializeType, mapType, rlen, clen, true, "fed_agg_binary_mixed_local");
		mo.setFedMapping(remoteMap);
		mo.getDataCharacteristics().set(rlen, clen, mo.getBlocksize(), mo.getNnz());
		if (DEBUG_KMEANS) {
			System.out.println("[DBG-KMEANS] aggBinary mixed-local remote materialize input=" + operand.getName()
				+ " dims=" + rlen + "x" + clen
				+ " oldType=" + requested
				+ " newType=" + remoteMap.getType()
				+ " anchorType=" + anchorType
				+ " inst=" + instString);
		}
		return ec.getMatrixLineagePair(operand);
	}

	private static FederationMap selectRemoteAnchor(MatrixLineagePair preferred, MatrixLineagePair fallback) {
		if (preferred != null && preferred.isFederated() && preferred.getFedMapping() != null
			&& !FEDLocalMaterializeUtil.hasLocalFederatedData(preferred.getFedMapping()))
			return preferred.getFedMapping();
		if (fallback != null && fallback.isFederated() && fallback.getFedMapping() != null
			&& !FEDLocalMaterializeUtil.hasLocalFederatedData(fallback.getFedMapping()))
			return fallback.getFedMapping();
		FederationMap anchor = remoteOnlyAnchor(preferred);
		if (anchor != null)
			return anchor;
		anchor = remoteOnlyAnchor(fallback);
		if (anchor != null)
			return anchor;
		return null;
	}

	private static FederationMap remoteOnlyAnchor(MatrixLineagePair pair) {
		if (pair == null || !pair.isFederated() || pair.getFedMapping() == null)
			return null;
		List<Pair<org.apache.sysds.runtime.controlprogram.federated.FederatedRange,
			org.apache.sysds.runtime.controlprogram.federated.FederatedData>> remote = new ArrayList<>();
		for (Pair<org.apache.sysds.runtime.controlprogram.federated.FederatedRange,
				org.apache.sysds.runtime.controlprogram.federated.FederatedData> entry : pair.getFedMapping().getMap()) {
			if (entry == null || entry.getValue() == null || entry.getValue().getAddress() == null)
				continue;
			remote.add(entry);
		}
		if (remote.isEmpty())
			return null;
		FType type = pair.getFedMapping().getType();
		if (remote.size() == 1 && (type == FType.OTHER || type == FType.PART))
			type = FType.FULL;
		return new FederationMap(pair.getFedMapping().getID(), remote, type);
	}

	private void writeInfoLog(MatrixLineagePair mo1, MatrixLineagePair mo2){
		FType mo1FType = (mo1.getFedMapping()==null) ? null : mo1.getFedMapping().getType();
		FType mo2FType = (mo2.getFedMapping()==null) ? null : mo2.getFedMapping().getType();
		LOG.info("Federated output flag would result in PART federated map and has been ignored in " + instString);
		LOG.info("Input 1 FType is " + mo1FType + " and input 2 FType " + mo2FType);
	}

	/**
	 * Sets the output with a federated mapping of overlapping partial aggregates.
	 * @param federationMap federated map from which the federated metadata is retrieved
	 * @param mo1 matrix object with number of rows used to set the number of rows of the output
	 * @param mo2 matrix object with number of columns used to set the number of columns of the output
	 * @param outputID ID of the output
	 * @param ec execution context
	 */
	@SuppressWarnings("unused")
	private void setPartialOutput(FederationMap federationMap, MatrixLineagePair mo1, MatrixLineagePair mo2,
		long outputID, ExecutionContext ec){
		MatrixObject out = ec.getMatrixObject(output);
		out.getDataCharacteristics().setDimension(mo1.getNumRows(), mo2.getNumColumns())
			.setBlocksize(mo1.getBlocksize());
		FederationMap outputFedMap = federationMap
			.copyWithNewIDAndRange(mo1.getNumRows(), mo2.getNumColumns(), outputID);
		out.setFedMapping(outputFedMap);
	}

	/**
	 * Sets the output with a federated map copied from federationMap input given.
	 * @param federationMap federation map to be set in output
	 * @param mo1 matrix object with number of rows used to set the number of rows of the output
	 * @param mo2 matrix object with number of columns used to set the number of columns of the output
	 * @param nnz the number of non-zeros of the output
	 * @param outputID ID of the output
	 * @param ec execution context
	 */
	private void setOutputFedMapping(FederationMap federationMap, MatrixLineagePair mo1,
		MatrixLineagePair mo2, long nnz, long outputID, ExecutionContext ec){
		MatrixObject out = ec.getMatrixObject(output);
		out.getDataCharacteristics()
			.setDimension(mo1.getNumRows(), mo2.getNumColumns())
			.setBlocksize(mo1.getBlocksize()).setNonZeros(nnz);
		// Robustness: copying a BROADCAST/FULL federation map can carry stale row/col extents from the
		// input (e.g., large broadcast matrix) into a smaller output (e.g., row vector). This can later
		// break local materialization (bind) and lead to shape explosions such as KxN instead of Kx1.
		FederationMap outMap = federationMap.copyWithNewID(outputID, mo2.getNumColumns());
		if(outMap.getType() == FType.BROADCAST || outMap.getType() == FType.FULL) {
			outMap.modifyFedRanges(mo1.getNumRows(), 0);
			outMap.modifyFedRanges(mo2.getNumColumns(), 1);
		}
		out.setFedMapping(outMap);
		if (DEBUG_KMEANS) {
			System.out.println("[DBG-KMEANS] aggBinary " + instOpcode + " out=" + output.getName()
				+ " dims=" + mo1.getNumRows() + "x" + mo2.getNumColumns()
				+ " nnz=" + nnz
				+ " ftype=" + federationMap.getType()
				+ " fedOut=" + _fedOut
				+ " inst=" + instString);
		}
	}

	private void aggregateLocally(FederationMap fedMap, boolean aggAdd, ExecutionContext ec,
		FederatedRequest... fr) {
		aggregateLocally(fedMap, aggAdd, ec, null, fr);
	}

	/**
	 * Collect cleanup IDs for local materialization: always remove the output ID and, additionally,
	 * any temporary PUT_VAR broadcast IDs (e.g., from broadcast/broadcastSliced) used in this request batch.
	 * This prevents unbounded accumulation of broadcast temporaries on federated workers (e.g., in kmeans loops).
	 */
	private static long[] collectCleanupIDs(long callInstID, FederatedRequest[] frSliced, FederatedRequest... fr) {
		java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
		ids.add(callInstID);
		if (frSliced != null && frSliced.length > 0 && frSliced[0] != null
			&& frSliced[0].getType() == RequestType.PUT_VAR) {
			ids.add(frSliced[0].getID());
		}
		if (fr != null) {
			for (FederatedRequest r : fr) {
				if (r != null && r.getType() == RequestType.PUT_VAR)
					ids.add(r.getID());
			}
		}
		long[] ret = new long[ids.size()];
		int i = 0;
		for (Long id : ids)
			ret[i++] = id;
		return ret;
	}

	/**
	 * Get the partial results and aggregate the partial results locally
	 * @param fedMap the federated mapping
	 * @param aggAdd indicates whether to aggregate the results by addition or binding
	 * @param ec execution context
	 * @param frSliced the federated request array from a sliced broadcast
	 * @param fr the previous federated requests
	 * NOTE: the last federated request fr has to be the instruction call
	 */
	private void aggregateLocally(FederationMap fedMap, boolean aggAdd, ExecutionContext ec,
		FederatedRequest[] frSliced, FederatedRequest... fr) {
		long callInstID = fr[fr.length - 1].getID();
		FederatedRequest frG = new FederatedRequest(RequestType.GET_VAR, callInstID);
		FederatedRequest frC = fedMap.cleanup(getTID(), collectCleanupIDs(callInstID, frSliced, fr));
		//execute federated operations and aggregate
		Future<FederatedResponse>[] ffr;
		if(frSliced != null)
			ffr = fedMap.execute(getTID(), frSliced, ArrayUtils.addAll(fr, frG, frC));
		else
			ffr = fedMap.execute(getTID(), ArrayUtils.addAll(fr, frG, frC));

		MatrixBlock ret;
		if ( aggAdd )
			ret = FederationUtils.aggAdd(ffr);
		else
			ret = FederationUtils.bind(ffr, false);
		ec.setMatrixOutput(output.getName(), ret);
		if (DEBUG_KMEANS) {
			System.out.println("[DBG-KMEANS] aggBinary " + instOpcode + " out=" + output.getName()
				+ " dims=" + ret.getNumRows() + "x" + ret.getNumColumns()
				+ " nnz=" + ret.getNonZeros()
				+ " local=true");
		}
	}

	private void aggregateLocallySingleWorker(FederationMap fedMap, ExecutionContext ec, FederatedRequest... fr) {
		//create GET calls on output
		long callInstID = fr[fr.length - 1].getID();
		FederatedRequest frG = new FederatedRequest(RequestType.GET_VAR, callInstID);
		FederatedRequest frC = fedMap.cleanup(getTID(), collectCleanupIDs(callInstID, null, fr));
		//execute federated operations
		Future<FederatedResponse>[] ffr = fedMap.execute(getTID(), ArrayUtils.addAll(fr, frG, frC));
		try {
			//use only one response (all responses contain the same result)
			MatrixBlock ret = (MatrixBlock) ffr[0].get().getData()[0];
			ec.setMatrixOutput(output.getName(), ret);
			if (DEBUG_KMEANS) {
				System.out.println("[DBG-KMEANS] aggBinary " + instOpcode + " out=" + output.getName()
					+ " dims=" + ret.getNumRows() + "x" + ret.getNumColumns()
					+ " nnz=" + ret.getNonZeros()
					+ " local=true(single)");
			}
		} catch(Exception ex){
			throw new DMLRuntimeException(ex);
		}
	}

	private static boolean isSameWorkerPool(FederationMap a, FederationMap b) {
		if (a == null || b == null)
			return false;
		if (a.getSize() != b.getSize())
			return false;
		int n = a.getSize();
		String[] as = new String[n];
		String[] bs = new String[n];
		for (int i = 0; i < n; i++) {
			as[i] = workerAddressKey(a.getMap().get(i).getValue() != null ?
				a.getMap().get(i).getValue().getAddress() : null);
			bs[i] = workerAddressKey(b.getMap().get(i).getValue() != null ?
				b.getMap().get(i).getValue().getAddress() : null);
		}
		java.util.Arrays.sort(as);
		java.util.Arrays.sort(bs);
		return java.util.Arrays.equals(as, bs);
	}

	private static boolean haveCompleteCoLocatedInputs(MatrixLineagePair left, MatrixLineagePair right) {
		return left != null && right != null && left.isFederated() && right.isFederated()
			&& isSameWorkerPool(left.getFedMapping(), right.getFedMapping())
			&& everyRangeCoversInput(left) && everyRangeCoversInput(right);
	}

	private static boolean everyRangeCoversInput(MatrixLineagePair input) {
		if (input.getFedMapping() == null || input.getFedMapping().getSize() == 0)
			return false;
		for (org.apache.sysds.runtime.controlprogram.federated.FederatedRange range :
				input.getFedMapping().getFederatedRanges()) {
			if (range == null)
				return false;
			long[] begin = range.getBeginDims();
			long[] end = range.getEndDims();
			if (begin == null || end == null || begin.length < 2 || end.length < 2
				|| begin[0] != 0 || begin[1] != 0
				|| end[0] != input.getNumRows() || end[1] != input.getNumColumns())
				return false;
		}
		return true;
	}

	private static long firstNonZeros(Future<FederatedResponse>[] responses) {
		if (responses == null || responses.length == 0)
			return -1;
		try {
			Object[] data = responses[0].get().getData();
			return data != null && data.length > 0 && data[0] instanceof Long ? (Long) data[0] : -1;
		}
		catch (Exception ex) {
			throw new DMLRuntimeException(ex);
		}
	}

	private static String workerAddressKey(InetSocketAddress address) {
		if (address == null)
			return "null";
		String host = address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
		return host + ":" + address.getPort();
	}
}

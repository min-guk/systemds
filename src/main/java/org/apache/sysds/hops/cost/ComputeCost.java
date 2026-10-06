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

package org.apache.sysds.hops.cost;

import java.util.function.IntToLongFunction;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DnnOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;

/**
 * Class with methods estimating compute costs of operations.
 */
public class ComputeCost {
	private static final Log LOG = LogFactory.getLog(ComputeCost.class.getName());

	/**
	 * Get compute cost for given HOP based on the number of floating point operations per output cell
	 * and the total number of output cells.
	 * @param currentHop for which compute cost is returned
	 * @return compute cost of currentHop as number of floating point operations
	 */
	public static double getHOPComputeCost(Hop currentHop){
		return getHOPComputeCost(currentHop, currentHop.getDim1(), currentHop.getDim2(),
			position -> currentHop.getInput(position).getDim1(),
			position -> currentHop.getInput(position).getDim2());
	}

	/**
	 * Evaluate the same operation with immutable, occurrence-resolved dimensions.
	 * Input dimensions are addressed by position, not Hop identity: a shared Hop
	 * may represent different values at distinct compiled input occurrences.
	 */
	public static double getHOPComputeCost(Hop currentHop, long outputRows, long outputCols,
			IntToLongFunction inputRows, IntToLongFunction inputCols) {
		double costs = 1;
		if( currentHop instanceof UnaryOp) {
			switch( ((UnaryOp)currentHop).getOp() ) {
				case ABS:
				case ROUND:
				case CEIL:
				case FLOOR:
				case SIGN:    costs = 1; break;
				case SPROP:
				case SQRT:    costs = 2; break;
				case EXP:     costs = 18; break;
				case SIGMOID: costs = 21; break;
				case LOG:
				case LOG_NZ:  costs = 32; break;
				case NCOL:
				case NROW:
				case PRINT:
				case ASSERT:
				case CAST_AS_BOOLEAN:
				case CAST_AS_DOUBLE:
				case CAST_AS_INT:
				case CAST_AS_MATRIX:
				case CAST_AS_SCALAR: costs = 1; break;
				case SIN:     costs = 18; break;
				case COS:     costs = 22; break;
				case TAN:     costs = 42; break;
				case ASIN:    costs = 93; break;
				case ACOS:    costs = 103; break;
				case ATAN:    costs = 40; break;
				case SINH:    costs = 93; break; // TODO:
				case COSH:    costs = 103; break;
				case TANH:    costs = 40; break;
				case CUMSUM:
				case CUMMIN:
				case CUMMAX:
				case CUMPROD: costs = 1; break;
				case CUMSUMPROD: costs = 2; break;
				default:
					LOG.warn("Cost model not "
						+ "implemented yet for: "+((UnaryOp)currentHop).getOp());
			}
		}
		else if( currentHop instanceof BinaryOp) {
			switch( ((BinaryOp)currentHop).getOp() ) {
				case MULT:
				case PLUS:
				case MINUS:
				case MIN:
				case MAX:
				case AND:
				case OR:
				case EQUAL:
				case NOTEQUAL:
				case LESS:
				case LESSEQUAL:
				case GREATER:
				case GREATEREQUAL:
				case CBIND:
				case RBIND:   costs = 1; break;
				case INTDIV:  costs = 6; break;
				case MODULUS: costs = 8; break;
				case DIV:     costs = 22; break;
				case LOG:
				case LOG_NZ:  costs = 32; break;
				case POW:     costs = (HopRewriteUtils.isLiteralOfValue(
					currentHop.getInput().get(1), 2) ? 1 : 16); break;
				case MINUS_NZ:
				case MINUS1_MULT: costs = 2; break;
				case MOMENT:
					int type = (int) (currentHop.getInput().get(1) instanceof LiteralOp ?
						HopRewriteUtils.getIntValueSafe((LiteralOp)currentHop.getInput().get(1)) : 2);
					switch( type ) {
						case 0: costs = 1; break; //count
						case 1: costs = 8; break; //mean
						case 2: costs = 16; break; //cm2
						case 3: costs = 31; break; //cm3
						case 4: costs = 51; break; //cm4
						case 5: costs = 16; break; //variance
					}
					break;
				case COV: costs = 23; break;
				default:
					LOG.warn("Cost model not "
						+ "implemented yet for: "+((BinaryOp)currentHop).getOp());
			}
		}
		else if( currentHop instanceof TernaryOp) {
			switch( ((TernaryOp)currentHop).getOp() ) {
				case IFELSE:
				case PLUS_MULT:
				case MINUS_MULT: costs = 2; break;
				case CTABLE:     costs = 3; break;
				case MOMENT:
					int type = (int) (currentHop.getInput().get(1) instanceof LiteralOp ?
						HopRewriteUtils.getIntValueSafe((LiteralOp)currentHop.getInput().get(1)) : 2);
					switch( type ) {
						case 0: costs = 2; break; //count
						case 1: costs = 9; break; //mean
						case 2: costs = 17; break; //cm2
						case 3: costs = 32; break; //cm3
						case 4: costs = 52; break; //cm4
						case 5: costs = 17; break; //variance
					}
					break;
				case COV: costs = 23; break;
				default:
					LOG.warn("Cost model not "
						+ "implemented yet for: "+((TernaryOp)currentHop).getOp());
			}
		}
		else if( currentHop instanceof NaryOp) {
			costs = HopRewriteUtils.isNary(currentHop, Types.OpOpN.MIN, Types.OpOpN.MAX, Types.OpOpN.PLUS) ?
				currentHop.getInput().size() : 1;
		}
			else if( currentHop instanceof ParameterizedBuiltinOp) {
				// Parameterized builtins typically scan their input(s) regardless of the output size.
				// Using only the output cell count can significantly under-estimate ops whose output
				// shrinks relative to the input (e.g., RMEMPTY/removeEmpty).
				ParameterizedBuiltinOp pb = (ParameterizedBuiltinOp) currentHop;
				if (pb.getOp() == Types.ParamBuiltinOp.RMEMPTY) {
					int position = pb.getParamIndexMap().getOrDefault("target", -1);
					double inSize = position >= 0
						? getSize(inputRows.applyAsLong(position), inputCols.applyAsLong(position))
						: getSize(outputRows, outputCols);
					double outSize = getSize(outputRows, outputCols);
					double effective = Math.max(inSize, outSize);
					costs = (outSize > 0) ? effective / outSize : effective;
				}
				else {
					costs = 1;
				}
			}
		else if( currentHop instanceof IndexingOp) {
			costs = 1;
		}
		else if( currentHop instanceof ReorgOp) {
			costs = 1;
		}
		else if( currentHop instanceof DnnOp) {
			switch( ((DnnOp)currentHop).getOp() ) {
				case BIASADD:
				case BIASMULT:
					costs = 2;
					break;
				default:
					LOG.warn("Cost model not "
						+ "implemented yet for: "+((DnnOp)currentHop).getOp());
			}
		}
		else if( currentHop instanceof QuaternaryOp) {
			double outputSize = getSize(outputRows, outputCols);
			if( outputSize <= 0 )
				outputSize = 1;
			double totalFlops;
			switch( ((QuaternaryOp)currentHop).getOp() ) {
				case WSLOSS:
				case WCEMM:
					totalFlops = 4d * getSize(inputRows.applyAsLong(0), inputCols.applyAsLong(0));
					break;
				case WDIVMM:
					long weightRows = inputRows.applyAsLong(0);
					long weightCols = inputCols.applyAsLong(0);
					long rank = currentHop.getInput().size() > 1
						? inputCols.applyAsLong(1) : -1;
					long weightNnz = currentHop.getInput().isEmpty()
						? -1 : currentHop.getInput(0).getNnz();
					totalFlops = getWdivmmComputeCost(weightRows, weightCols, weightNnz, rank);
					break;
				case WSIGMOID:
				case WUMM:
					totalFlops = 3d * getSize(inputRows.applyAsLong(0), inputCols.applyAsLong(0));
					break;
				default:
					LOG.warn("Cost model not "
						+ "implemented yet for: "+((QuaternaryOp)currentHop).getOp());
					totalFlops = outputSize;
			}
			costs = totalFlops / outputSize;
		}
		else if( currentHop instanceof AggBinaryOp) {
			//outer product template w/ matrix-matrix
			//or row template w/ matrix-vector or matrix-matrix
			long rows = inputRows.applyAsLong(0), cols = inputCols.applyAsLong(0);
			costs = 2d * cols;
			if(currentHop.getInput(0).dimsKnown(true))
				costs *= currentHop.getInput(0).getSparsity();
			else if(rows > 0 && cols > 0 && currentHop.getInput(0).getNnz() >= 0)
				costs *= Math.min(1d, currentHop.getInput(0).getNnz() / (double)rows / cols);
		}
		else if( currentHop instanceof AggUnaryOp) {
			switch(((AggUnaryOp)currentHop).getOp()) {
				case SUM:    costs = 4; break;
				case SUM_SQ: costs = 5; break;
				case MIN:
				case MAX:    costs = 1; break;
				default:
					LOG.warn("Cost model not "
						+ "implemented yet for: "+((AggUnaryOp)currentHop).getOp());
			}
			switch(((AggUnaryOp)currentHop).getDirection()) {
				case Col: costs *= Math.max(inputRows.applyAsLong(0),1); break;
				case Row: costs *= Math.max(inputCols.applyAsLong(0),1); break;
				case RowCol: costs *= getSize(inputRows.applyAsLong(0), inputCols.applyAsLong(0)); break;
			}
		}

		//scale by current output size in order to correctly reflect
		//a mix of row and cell operations in the same fused operator
		//(e.g., row template with fused column vector operations)
		costs *= getSize(outputRows, outputCols);
		return costs;
	}

	/** Runtime WDivMM work over active weights and the factor rank. */
	public static double getWdivmmComputeCost(long weightRows, long weightCols,
			long weightNnz, long rank) {
		double cells = weightNnz >= 0 ? weightNnz : getSize(weightRows, weightCols);
		return 4d * Math.max(rank, 1L) * Math.max(cells, 0d);
	}

	/**
	 * Number of logical cells without overflowing long for large shape estimates.
	 */
	private static double getSize(long rows, long cols) {
		return (double)Math.max(rows,1) * Math.max(cols,1);
	}
}

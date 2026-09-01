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

package org.apache.sysds.runtime.instructions.cp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.functionobjects.CM;
import org.apache.sysds.runtime.functionobjects.COV;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.CMOperator;
import org.apache.sysds.runtime.matrix.operators.CMOperator.AggregateOperationTypes;
import org.apache.sysds.runtime.matrix.operators.COVOperator;
import org.junit.Test;

public class CMCovCPInstructionUtilsTest {
	private static final int THREADS = 2;
	private static final double EPS = 1e-12;

	@Test
	public void normalizesOnlyRowVectors() {
		MatrixBlock row = matrix(1, 4, 1, 2, 3, 4);
		MatrixBlock column = matrix(4, 1, 1, 2, 3, 4);
		MatrixBlock matrix = matrix(2, 2, 1, 2, 3, 4);

		MatrixBlock normalizedRow = CMCovCPInstructionUtils.normalizeVectorOrientation(row, THREADS);
		assertNotSame(row, normalizedRow);
		assertEquals(4, normalizedRow.getNumRows());
		assertEquals(1, normalizedRow.getNumColumns());
		for(int i = 0; i < 4; i++)
			assertEquals(i + 1, normalizedRow.get(i, 0), EPS);
		assertSame(column, CMCovCPInstructionUtils.normalizeVectorOrientation(column, THREADS));
		assertSame(matrix, CMCovCPInstructionUtils.normalizeVectorOrientation(matrix, THREADS));
	}

	@Test
	public void rowAndColumnVectorsHaveIdenticalWeightedStatistics() {
		MatrixBlock rowX = matrix(1, 4, 1, 2, 4, 8);
		MatrixBlock rowY = matrix(1, 4, 2, 5, 7, 11);
		MatrixBlock rowW = matrix(1, 4, 1, 2, 3, 4);
		MatrixBlock colX = matrix(4, 1, 1, 2, 4, 8);
		MatrixBlock colY = matrix(4, 1, 2, 5, 7, 11);
		MatrixBlock colW = matrix(4, 1, 1, 2, 3, 4);

		CMOperator cm = new CMOperator(CM.getCMFnObject(AggregateOperationTypes.VARIANCE),
			AggregateOperationTypes.VARIANCE, THREADS);
		COVOperator cov = new COVOperator(COV.getCOMFnObject(), THREADS);
		MatrixBlock normalizedX = CMCovCPInstructionUtils.normalizeVectorOrientation(rowX, THREADS);
		MatrixBlock normalizedY = CMCovCPInstructionUtils.normalizeVectorOrientation(rowY, THREADS);
		MatrixBlock normalizedW = CMCovCPInstructionUtils.normalizeVectorOrientation(rowW, THREADS);

		assertEquals(colX.cmOperations(cm, colW).getRequiredResult(cm),
			normalizedX.cmOperations(cm, normalizedW).getRequiredResult(cm), EPS);
		assertEquals(colX.covOperations(cov, colY, colW).getRequiredResult(cov),
			normalizedX.covOperations(cov, normalizedY, normalizedW).getRequiredResult(cov), EPS);
	}

	@Test
	public void normalizationPreservesDimensionValidation() {
		CMOperator cm = new CMOperator(CM.getCMFnObject(AggregateOperationTypes.VARIANCE),
			AggregateOperationTypes.VARIANCE, THREADS);
		MatrixBlock nonVector = matrix(2, 2, 1, 2, 3, 4);
		MatrixBlock mismatchedWeights = matrix(1, 3, 1, 1, 1);
		MatrixBlock row = matrix(1, 4, 1, 2, 3, 4);

		assertThrows(DMLRuntimeException.class,
			() -> CMCovCPInstructionUtils.normalizeVectorOrientation(nonVector, THREADS).cmOperations(cm));
		assertThrows(DMLRuntimeException.class, () ->
			CMCovCPInstructionUtils.normalizeVectorOrientation(row, THREADS).cmOperations(cm,
				CMCovCPInstructionUtils.normalizeVectorOrientation(mismatchedWeights, THREADS)));
	}

	private static MatrixBlock matrix(int rows, int cols, double... values) {
		return new MatrixBlock(rows, cols, values);
	}
}

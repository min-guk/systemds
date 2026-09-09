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

import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.lineage.LineageCacheConfig;
import org.apache.sysds.runtime.lineage.LineageItem;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.Operator;
import org.apache.sysds.runtime.util.CommonThreadPool;

public class PrefetchCPInstruction extends UnaryCPInstruction {
	private PrefetchCPInstruction(Operator op, CPOperand in, CPOperand out, String opcode, String istr) {
		super(CPType.Prefetch, op, in, out, opcode, istr);
	}
	
	public static PrefetchCPInstruction parseInstruction (String str) {
		InstructionUtils.checkNumFields(str, 3);
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		CPOperand in = new CPOperand(parts[1]);
		CPOperand out = new CPOperand(parts[2]);
		// int k = Integer.parseInt(parts[3]);
		return new PrefetchCPInstruction(null, in, out, opcode, str);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		/*
		 * A planner-selected LOCAL/REFED_LOCAL action is a physical placement
		 * boundary, not an asynchronous performance hint.  The ordinary prefetch
		 * implementation aliases the input MatrixObject and merely schedules an
		 * acquire in the background; a federated input therefore continues to
		 * publish its FederationMap through the output name.  That is unsafe for an
		 * exact planner boundary followed by a cpvar or another non-materializing
		 * consumer.  Materialize synchronously and publish a distinct local output;
		 * ExecutionContext clears any pre-created output FederationMap.
		 *
		 * Prefetches without planner authority retain their historical asynchronous
		 * behavior below.
		 */
		if(getPlannerSyntheticActionKey() != null) {
			if(input1.isFrame()) {
				FrameBlock materialized = ec.getFrameObject(input1).acquireReadAndRelease();
				ec.setFrameOutput(output.getName(), materialized);
				return;
			}
			LineageItem li = !LineageCacheConfig.ReuseCacheType.isNone()
				? getLineageItem(ec).getValue() : null;
			MatrixBlock materialized = ec.getMatrixObject(input1).acquireReadAndRelease();
			ec.setMatrixOutputAndLineage(output.getName(), materialized, li);
			return;
		}

		// TODO: handle non-matrix objects
		ec.setVariable(output.getName(), ec.getMatrixObject(input1));
		LineageItem li = !LineageCacheConfig.ReuseCacheType.isNone() ? getLineageItem(ec).getValue() : null;

		// Note, a Prefetch instruction doesn't guarantee an asynchronous execution.
		// If the next instruction which takes this output as an input comes before
		// the prefetch thread triggers, that instruction will start the operations.
		// In that case this Prefetch instruction will act like a NOOP. 
		// Saving the lineage item inside the matrix object will replace the pre-attached
		// lineage item (e.g. mapmm). Hence, passing separately.
		CommonThreadPool.getDynamicPool().submit(new TriggerPrefetchTask(ec.getMatrixObject(output), li));
	}
}

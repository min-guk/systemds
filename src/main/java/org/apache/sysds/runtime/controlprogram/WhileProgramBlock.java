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

package org.apache.sysds.runtime.controlprogram;

import java.util.ArrayList;
import java.util.Arrays;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.hops.recompile.Recompiler.ResetType;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.DMLScriptException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject.UpdateType;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.BooleanObject;
import org.apache.sysds.runtime.lineage.LineageDedupUtils;


public class WhileProgramBlock extends ProgramBlock 
{
	private ArrayList<Instruction> _predicate;
	private ArrayList<ProgramBlock> _childBlocks;

	public WhileProgramBlock(Program prog, ArrayList<Instruction> predicate) {
		super(prog);
		_predicate = predicate;
		_childBlocks = new ArrayList<>(); 
	}
	
	public void addProgramBlock(ProgramBlock childBlock) {
		_childBlocks.add(childBlock);
	}

	public ArrayList<Instruction> getPredicate() { 
		return _predicate; 
	}
	
	public void setPredicate( ArrayList<Instruction> predicate ) { 
		_predicate = predicate;
	}
	
	@Override
	public ArrayList<ProgramBlock> getChildBlocks() {
		return _childBlocks;
	}
	
	@Override
	public boolean isNested() {
		return true;
	}
	
	private BooleanObject executePredicate(ExecutionContext ec) 
	{
		BooleanObject result;
		try
		{
			if( _sb!=null )
			{
				WhileStatementBlock wsb = (WhileStatementBlock)_sb;
				Hop predicateOp = wsb.getPredicateHops();
				boolean recompile = wsb.requiresPredicateRecompilation();
				result = (BooleanObject) executePredicate(_predicate, predicateOp, recompile, ValueType.BOOLEAN, ec);
			}
			else
				result = (BooleanObject) executePredicate(_predicate, null, false, ValueType.BOOLEAN, ec);
		}
		catch(Exception ex) {
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Failed to evaluate the while predicate.", ex);
		}
		
		//(guaranteed to be non-null, see executePredicate/getScalarInput)
		return result;
	}
	
	@Override
	public void execute(ExecutionContext ec)
	{
		//execute while loop
		try(org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyRuntime.Region plannedLoop =
				org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyRuntime.enter(ec,
					org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.ScopeKind.LOOP_INVOCATION,
					_sb == null ? -1 : _sb.getSBID()))
		{
			// prepare update in-place variables
			UpdateType[] flags = prepareUpdateInPlaceVariables(ec, _tid);
			
			//dynamically recompile entire loop body (according to loop inputs)
			//pass loop not just child blocks for correct size propagation
			StatementBlock sb = getStatementBlock();
			if( sb != null )
				Recompiler.recompileFunctionOnceIfNeeded(
					sb.isRecompileOnce(), Arrays.asList(this), _tid, true, ResetType.RESET_KNOWN_DIMS, ec);
			
			// compute and store the number of distinct paths
			if (DMLScript.LINEAGE_DEDUP)
				ec.getLineage().initializeDedupBlock(this, ec);
			
			// Run the predicate as an ordinary consumer. Next -> Head is validated only
			// after this predicate proves that another body iteration will execute.
			boolean continueLoop = executePredicate(ec).getBooleanValue();
			if( continueLoop ) try(MemoLoopRuntime.Scope loopContract = MemoLoopRuntime.enter(_sb, ec, _tid)) {
			while( continueLoop ) {
				try(org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyRuntime.Region plannedIteration =
					org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyRuntime.enter(ec,
						org.apache.sysds.hops.fedplanner.memo.MemoPlanRegistry.ScopeKind.LOOP_ITERATION,
						_sb == null ? -1 : _sb.getSBID())) {
				if (DMLScript.LINEAGE_DEDUP)
					ec.getLineage().resetDedupPath();

				if (DMLScript.LINEAGE_DEDUP)
					// create a new dedup map, if needed, to trace this iteration
					ec.getLineage().createDedupPatch(this, ec);
				
				//execute all child blocks
				for (int i=0 ; i < _childBlocks.size() ; i++) {
					_childBlocks.get(i).execute(ec);
				}
				}
				
				if (DMLScript.LINEAGE_DEDUP) {
					LineageDedupUtils.replaceLineage(ec);
					// hook the dedup map to the main lineage trace
					ec.getLineage().traceCurrentDedupPath(this, ec);
				}
				continueLoop = executePredicate(ec).getBooleanValue();
				loopContract.iterationCompleted(continueLoop);
			}
			loopContract.complete();
			}
			
			// clear current LineageDedupBlock
			if (DMLScript.LINEAGE_DEDUP)
				ec.getLineage().clearDedupBlock();
			
			// reset update-in-place variables
			resetUpdateInPlaceVariableFlags(ec, flags);
		}
		catch (DMLScriptException e) {
			//propagate stop call
			throw e;
		}
		catch (Exception e) {
			throw new DMLRuntimeException(printBlockErrorLocation() + "Error evaluating while program block", e);
		}
		
		//execute exit instructions
		executeExitInstructions("while", ec);
	}
	
	public void setChildBlocks(ArrayList<ProgramBlock> childs) {
		_childBlocks = childs;
	}
	
	@Override
	public String printBlockErrorLocation(){
		return "ERROR: Runtime error in while program block generated from while statement block between lines " + _beginLine + " and " + _endLine + " -- ";
	}
}

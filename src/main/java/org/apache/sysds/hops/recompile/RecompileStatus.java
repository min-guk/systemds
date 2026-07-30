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

package org.apache.sysds.hops.recompile;

import org.apache.sysds.hops.recompile.Recompiler.ResetType;
import org.apache.sysds.runtime.controlprogram.ProgramBlock;
import org.apache.sysds.runtime.meta.DataCharacteristics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RecompileStatus 
{
	//immutable flags for recompilation configurations
	private final long _tid;               // thread-id, 0 if main thread
	private final boolean _inplace;        // in-place recompilation, false for rewrites
	private final ResetType _reset;        // reset type for program compilation
	private final boolean _initialCodegen; // initial codegen compilation (no recompilation)
	
	//track if parts of recompiled program still require recompilation
	private boolean _requiresRecompile = false;
	
	//collection of extracted statistics for control flow reconciliation
	private final Map<String, DataCharacteristics> _lastTWrites;
	// MatrixObjects synthesized by statistics propagation carry dimensions only. Their lack of a
	// FederationMap is not evidence that the runtime value is local, so placement validation must
	// treat these variable names as unobserved until an actual ExecutionContext value is available.
	private final Set<String> _unknownFederatedPlacementVars;
	
	public RecompileStatus() {
		this(0, true, ResetType.NO_RESET, false);
	}
	
	public RecompileStatus(boolean initialCodegen) {
		this(0, true, ResetType.NO_RESET, initialCodegen);
	}
	
	public RecompileStatus(long tid, boolean inplace, ResetType reset, boolean initialCodegen) {
		_lastTWrites = new HashMap<>();
		_unknownFederatedPlacementVars = new HashSet<>();
		_tid = tid;
		_inplace = inplace;
		_reset = reset;
		_initialCodegen = initialCodegen;
	}
	
	public Map<String, DataCharacteristics> getTWriteStats() {
		return _lastTWrites;
	}

	public void markFederatedPlacementUnknown(Iterable<String> variableNames) {
		if (variableNames == null)
			return;
		for (String variableName : variableNames)
			if (variableName != null && !variableName.isEmpty())
				_unknownFederatedPlacementVars.add(variableName);
	}

	public Set<String> getUnknownFederatedPlacementVars() {
		return _unknownFederatedPlacementVars;
	}

	public void mergeUnknownFederatedPlacementVars(RecompileStatus that) {
		if (that != null)
			_unknownFederatedPlacementVars.addAll(that._unknownFederatedPlacementVars);
	}
	
	public long getTID() {
		return _tid;
	}
	
	public boolean hasThreadID() {
		return ProgramBlock.isThreadID(_tid);
	}
	
	public boolean isInPlace() {
		return _inplace;
	}
	
	public boolean isReset() {
		return _reset.isReset();
	}
	
	public ResetType getReset() {
		return _reset;
	}
	
	public boolean isInitialCodegen() {
		return _initialCodegen;
	}
	
	public void trackRecompile(boolean flag) {
		_requiresRecompile |= flag;
	}
	
	public boolean requiresRecompile() {
		return _requiresRecompile;
	}

	@Override
	public Object clone() {
		RecompileStatus ret = new RecompileStatus(
			_tid, _inplace, _reset, _initialCodegen);
		ret._lastTWrites.putAll(_lastTWrites);
		ret._unknownFederatedPlacementVars.addAll(_unknownFederatedPlacementVars);
		return ret;
	}
}

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

package org.apache.sysds.lops;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.common.Builtins;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;

public class FunctionCallCP extends Lop
{
	public static final String MEMO_RETURNS_PREFIX = "memo_returns=";
	private String _fnamespace;
	private String _fname;
	private String[] _inputNames;
	private String[] _outputNames;
	private ArrayList<Lop> _outputLops = null;
	private final boolean _opt;
	private final int _numThreads;
	private long _memoReturnSnapshot = -1;
	private Map<Integer, List<Long>> _memoReturnDefinitionCandidates = Map.of();
	private Map<Integer, Long> _memoReturnTargets = Map.of();

	public FunctionCallCP(ArrayList<Lop> inputs, String fnamespace, String fname, String[] inputNames,
		String[] outputNames, ArrayList<Hop> outputHops, boolean opt, ExecType et) {
		this(inputs, fnamespace, fname, inputNames, outputNames, outputHops, opt, et, 1);
		
	}

	public FunctionCallCP(ArrayList<Lop> inputs, String fnamespace, String fname, String[] inputNames,
		String[] outputNames, ArrayList<Hop> outputHops, boolean opt, ExecType et, int threads) {
		this(inputs, fnamespace, fname, inputNames, outputNames, opt, et, threads);
		if(outputHops != null) {
			_outputLops = new ArrayList<>();
			setLevel();
			for(Hop h : outputHops) {
				Lop outputLop = h.constructLops();
				_outputLops.add( outputLop );
				addOutput(outputLop);
				// Update the output level if necessary for correct instruction ordering
				if(outputLop.getLevel() <= getLevel()) {
					outputLop.updateLevel(getLevel()+1);
				}
			}
		}
	}
	
	public FunctionCallCP(ArrayList<Lop> inputs, String fnamespace, String fname, String[] inputNames,
		String[] outputNames, boolean opt, ExecType et) {
		this(inputs, fnamespace, fname, inputNames, outputNames, opt, et, 1);
	}

	public FunctionCallCP(ArrayList<Lop> inputs, String fnamespace, String fname, String[] inputNames,
		String[] outputNames, boolean opt, ExecType et, int threads) {
		super(Lop.Type.FunctionCallCP, DataType.UNKNOWN, ValueType.UNKNOWN);
		// note: data scalar in order to prevent generation of redundant createvar, rmvar

		_fnamespace = fnamespace;
		_fname = fname;
		_inputNames = inputNames;
		_outputNames = outputNames;

		// wire inputs
		for(Lop in : inputs) {
			addInput(in);
			in.addOutput(this);
		}

		// lop properties: always in CP
		lps.setProperties(inputs, et);
		_opt = opt;
		_numThreads = threads;
	}

	public ArrayList<Lop> getFunctionOutputs() {
		return _outputLops;
	}

	public String getFnamespace() {
		return _fnamespace;
	}

	public String getFunctionName(){
		return _fname;
	}	

	public boolean requiresOutputCreateVar() {
		return !_fname.equalsIgnoreCase(Builtins.REMOVE.getName());
	}

	public void setMemoReturnDefinitions(long snapshot, Map<Integer, Long> definitions) {
		setMemoReturnDefinitions(snapshot, definitions, definitions);
	}

	public void setMemoReturnDefinitions(long snapshot, Map<Integer, Long> definitions,
		Map<Integer, Long> targets) {
		Map<Integer, List<Long>> candidates = new LinkedHashMap<>();
		if(definitions != null)
			definitions.forEach((position, origin) -> candidates.put(position, List.of(origin)));
		setMemoReturnDefinitionCandidates(snapshot, candidates, targets);
	}

	public void setMemoReturnDefinitionCandidates(long snapshot, Map<Integer, List<Long>> definitions,
		Map<Integer, Long> targets) {
		validateMemoReturnDefinitionCandidates(snapshot, definitions, targets);
		Map<Integer, List<Long>> checked = new LinkedHashMap<>();
		Map<Integer, Long> checkedTargets = new LinkedHashMap<>();
		for(Map.Entry<Integer, List<Long>> entry : definitions.entrySet()) {
			checked.put(entry.getKey(), entry.getValue().stream().distinct().toList());
			checkedTargets.put(entry.getKey(), targets.get(entry.getKey()));
		}
		_memoReturnSnapshot = snapshot;
		_memoReturnDefinitionCandidates = Map.copyOf(checked);
		_memoReturnTargets = Map.copyOf(checkedTargets);
	}

	/** Non-mutating validation shared by whole-plan lowering and the publishing setter. */
	public void validateMemoReturnDefinitionCandidates(long snapshot,
		Map<Integer, List<Long>> definitions, Map<Integer, Long> targets) {
		if(snapshot < 0 || definitions == null || definitions.isEmpty() || targets == null
			|| !definitions.keySet().equals(targets.keySet()))
			throw new LopsException("Memo function returns require a snapshot and at least one output definition.");
		for(Map.Entry<Integer, List<Long>> entry : definitions.entrySet()) {
			List<Long> candidates = entry.getValue() == null ? List.of() : entry.getValue().stream().distinct().toList();
			if(entry.getKey() == null || entry.getKey() < 0 || entry.getKey() >= _outputNames.length || candidates.isEmpty()
				|| candidates.stream().anyMatch(origin -> origin == null || origin < 0)
				|| targets.get(entry.getKey()) == null || targets.get(entry.getKey()) < 0)
				throw new LopsException("Invalid Memo function return definition " + entry + '.');
		}
	}
	
	@Override
	public String toString() {
		return "function call: " + DMLProgram.constructFunctionKey(_fnamespace, _fname);
	}

	private String getInstructionsMultipleReturnBuiltins(String[] inputs, String[] outputs) {
		StringBuilder sb = new StringBuilder();
		sb.append(getExecType());
		
		sb.append(Lop.OPERAND_DELIMITOR); 
		sb.append(_fname.toLowerCase());
		
		for(int i=0; i< inputs.length; i++) {
			sb.append(Lop.OPERAND_DELIMITOR);
			sb.append( getInputs().get(i).prepInputOperand(inputs[i]) );
		}
		
		for(int i=0; i< _outputNames.length; i++) {
			sb.append(Lop.OPERAND_DELIMITOR);
			sb.append(_outputNames[i]);
		}

		if(getExecType().equals(ExecType.CP)){
			if(!(_fname.toLowerCase().equals(Opcodes.REMOVE.toString()))){
				sb.append(Lop.OPERAND_DELIMITOR);
				sb.append(_numThreads);
			}
		}
		
		return sb.toString();
	}
	
	/**
	 * Method to generate instructions for external functions as well as builtin functions with multiple returns.
	 * Builtin functions have their namespace set to DMLProgram.INTERNAL_NAMESPACE ("_internal").
	 */
	@Override
	public String getInstructions(String[] inputs, String[] outputs)
	{		
		// Handle internal builtin functions
		if (_fnamespace.equalsIgnoreCase(DMLProgram.INTERNAL_NAMESPACE) ) {
			return getInstructionsMultipleReturnBuiltins(inputs, outputs);
		}

		StringBuilder inst = InstructionUtils.getStringBuilder();
		inst.append(getExecType());
		
		inst.append(Lop.OPERAND_DELIMITOR); 
		inst.append(Opcodes.FCALL.getType());
		inst.append(Lop.OPERAND_DELIMITOR);
		inst.append(_fnamespace);
		inst.append(Lop.OPERAND_DELIMITOR);
		inst.append(_fname);
		inst.append(Lop.OPERAND_DELIMITOR);
		inst.append(_opt);
		inst.append(Lop.OPERAND_DELIMITOR);
		inst.append(inputs.length);
		inst.append(Lop.OPERAND_DELIMITOR);
		inst.append(_outputNames.length);
		
		for(int i=0; i<inputs.length; i++) {
			inst.append(Lop.OPERAND_DELIMITOR);
			inst.append(_inputNames[i]);
			inst.append("=");
			inst.append( getInputs().get(i).prepInputOperand(inputs[i]) );
		}

		// TODO function output dataops (phase 3) - take 'outputs' into account
		for( String out : _outputNames ) {
			inst.append(Lop.OPERAND_DELIMITOR);
			inst.append(out);
		}
		if(!_memoReturnDefinitionCandidates.isEmpty()) {
			inst.append(Lop.OPERAND_DELIMITOR);
			inst.append(MEMO_RETURNS_PREFIX).append(_memoReturnSnapshot).append(':');
			boolean first = true;
			for(Map.Entry<Integer, List<Long>> entry :
				new java.util.TreeMap<>(_memoReturnDefinitionCandidates).entrySet()) {
				if(!first) inst.append(',');
				long target = _memoReturnTargets.get(entry.getKey());
				inst.append(entry.getKey()).append('=').append(entry.getValue().stream()
					.map(String::valueOf).collect(java.util.stream.Collectors.joining("|")));
				if(entry.getValue().size() != 1 || target != entry.getValue().get(0))
					inst.append('>').append(target);
				first = false;
			}
		}

		return inst.toString();
	}
}

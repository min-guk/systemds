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

package org.apache.sysds.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.runtime.controlprogram.Program;

public class DMLProgram 
{
	public static final String DEFAULT_NAMESPACE = ".defaultNS";
	public static final String BUILTIN_NAMESPACE = ".builtinNS";
	public static final String INTERNAL_NAMESPACE = "_internal"; // used for multi-return builtin functions
	
	private ArrayList<StatementBlock> _blocks;
	private Map<String, FunctionDictionary<FunctionStatementBlock>> _namespaces;
	private boolean _containsRemoteParfor;
	private final AtomicReference<PlacementAnalysis> _placementAnalysisAuthority = new AtomicReference<>();
	
	public DMLProgram(){
		_blocks = new ArrayList<>();
		_namespaces = new HashMap<>();
		_containsRemoteParfor = false;
	}
	
	public DMLProgram(String namespace) {
		this();
		createNamespace(namespace);
	}

	PlacementAnalysis bindPlacementAnalysisAtFinalHopBoundary() {
		PlacementAnalysis current = _placementAnalysisAuthority.get();
		if(current == null) {
			PlacementAnalysis candidate = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(this);
			_placementAnalysisAuthority.compareAndSet(null, candidate);
			current = _placementAnalysisAuthority.get();
		}
		return current;
	}

	public PlacementAnalysis requirePlacementAnalysisAuthority() {
		PlacementAnalysis current = _placementAnalysisAuthority.get();
		if(current == null)
			throw new IllegalStateException("Program has no authoritative placement analysis");
		return current;
	}

	public void requirePlacementAnalysisAuthority(PlacementAnalysis candidate) {
		if(candidate == null || _placementAnalysisAuthority.get() != candidate)
			throw new IllegalArgumentException("Placement analysis is not the canonical program owner");
	}

	void requirePlacementAnalysisUnboundForHopRewrite() {
		if(_placementAnalysisAuthority.get() != null)
			throw new IllegalStateException("Compiled Hop structure cannot be rewritten after placement authority binding");
	}
	
	public Map<String,FunctionDictionary<FunctionStatementBlock>> getNamespaces(){
		return _namespaces;
	}

	public void addStatementBlock(StatementBlock b){
		_blocks.add(b);
	}
	
	public int getNumStatementBlocks(){
		return _blocks.size();
	}
	
	public void setContainsRemoteParfor(boolean flag) {
		_containsRemoteParfor = flag;
	}
	
	public boolean containsRemoteParfor() {
		return _containsRemoteParfor;
	}
	
	public static boolean isInternalNamespace(String namespace) {
		return DEFAULT_NAMESPACE.equals(namespace)
			|| BUILTIN_NAMESPACE.equals(namespace)
			|| INTERNAL_NAMESPACE.equals(namespace);
	}
	
	public FunctionDictionary<FunctionStatementBlock> createNamespace(String namespace) {
		// create on demand, necessary to avoid overwriting existing functions
		if( !_namespaces.containsKey(namespace) )
			_namespaces.put(namespace, new FunctionDictionary<>());
		return _namespaces.get(namespace);
	}

	/**
	 * 
	 * @param fkey   function key as concatenation of namespace and function name 
	 *               (see DMLProgram.constructFunctionKey)
	 * @return function statement block
	 */
	public FunctionStatementBlock getFunctionStatementBlock(String fkey) {
		String[] tmp = splitFunctionKey(fkey);
		return tmp.length < 2 ? null : 
			getFunctionStatementBlock(tmp[0], tmp[1]);
	}
	
	public void removeFunctionStatementBlock(String fkey) {
		String[] tmp = splitFunctionKey(fkey);
		if( tmp.length == 2 )
			removeFunctionStatementBlock(tmp[0], tmp[1]);
	}
	
	public FunctionStatementBlock getFunctionStatementBlock(String namespaceKey, String functionName) {
		FunctionDictionary<FunctionStatementBlock> dict = getNamespaces().get(namespaceKey);
		if (dict == null)
			return null;
	
		// for the namespace DMLProgram, get the specified function (if exists) in its current namespace
		return dict.getFunction(functionName);
	}
	
	public void removeFunctionStatementBlock(String namespaceKey, String functionName) {
		FunctionDictionary<FunctionStatementBlock> dict = getNamespaces().get(namespaceKey);
		// for the namespace DMLProgram, get the specified function (if exists) in its current namespace
		if (dict != null)
			dict.removeFunction(functionName);
	}
	
	public Map<String, FunctionStatementBlock> getFunctionStatementBlocks(String namespaceKey) {
		FunctionDictionary<FunctionStatementBlock> dict = getNamespaces().get(namespaceKey);
		if (dict == null)
			throw new LanguageException("ERROR: namespace " + namespaceKey + " is undefined");
		
		// for the namespace DMLProgram, get the functions in its current namespace
		return dict.getFunctions();
	}
	
	public boolean hasFunctionStatementBlocks() {
		return _namespaces.values().stream()
			.anyMatch(dict -> !dict.getFunctions().isEmpty());
	}
	
	public List<FunctionStatementBlock> getFunctionStatementBlocks() {
		List<FunctionStatementBlock> ret = new ArrayList<>();
		for( FunctionDictionary<FunctionStatementBlock> dict : _namespaces.values() )
			ret.addAll(dict.getFunctions().values());
		return ret;
	}
	
	public Map<String,FunctionStatementBlock> getNamedNSFunctionStatementBlocks() {
		Map<String, FunctionStatementBlock> ret = new HashMap<>();
		for( Entry<String, FunctionDictionary<FunctionStatementBlock>> namespace : _namespaces.entrySet() )
			for( Entry<String, FunctionStatementBlock> function : namespace.getValue().getFunctions().entrySet() ) {
				String key = DEFAULT_NAMESPACE.equals(namespace.getKey()) ? function.getKey()
					: constructFunctionKey(namespace.getKey(), function.getKey());
				if(ret.put(key, function.getValue()) != null)
					throw new LanguageException("Duplicate qualified function root: " + key);
			}
		return ret;
	}
	
	public FunctionDictionary<FunctionStatementBlock> getDefaultFunctionDictionary() {
		return _namespaces.get(DEFAULT_NAMESPACE);
	}
	
	public FunctionDictionary<FunctionStatementBlock> getBuiltinFunctionDictionary() {
		return _namespaces.get(BUILTIN_NAMESPACE);
	}
	
	public FunctionDictionary<FunctionStatementBlock> getFunctionDictionary(String namespace) {
		return _namespaces.get(namespace);
	}
	
	public void addFunctionStatementBlock(String fname, FunctionStatementBlock fsb) {
		addFunctionStatementBlock(DEFAULT_NAMESPACE, fname, fsb);
	}

	public void addFunctionStatementBlock( String namespace, String fname, FunctionStatementBlock fsb ) {
		FunctionDictionary<FunctionStatementBlock> dict = getNamespaces().get(namespace);
		if (dict == null)
			throw new LanguageException( "Namespace does not exist." );
		dict.addFunction(fname, fsb);
	}
	
	public void copyOriginalFunctions() {
		for( FunctionDictionary<?> dict : getNamespaces().values() )
			dict.copyOriginalFunctions();
	}
	
	public ArrayList<StatementBlock> getStatementBlocks(){
		return _blocks;
	}
	
	public void setStatementBlocks(ArrayList<StatementBlock> passed){
		_blocks = passed;
	}
	
	public StatementBlock getStatementBlock(int i){
		return _blocks.get(i);
	}

	public void mergeStatementBlocks(){
		_blocks = StatementBlock.mergeStatementBlocks(_blocks);
	}
	
	public void hoistFunctionCallsFromExpressions() {
		try {
			//handle statement blocks of all functions
			for( FunctionStatementBlock fsb : getFunctionStatementBlocks() )
				StatementBlock.rHoistFunctionCallsFromExpressions(fsb, this);
			//handle statement blocks of main program
			ArrayList<StatementBlock> tmp = new ArrayList<>();
			for( StatementBlock sb : _blocks )
				tmp.addAll(StatementBlock.rHoistFunctionCallsFromExpressions(sb, this));
			_blocks = tmp;
		}
		catch(LanguageException ex) {
			throw new RuntimeException(ex);
		}
	}

	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		
		// for each namespace, display all functions
		for (String namespaceKey : this.getNamespaces().keySet()){
			
			sb.append("NAMESPACE = " + namespaceKey + "\n");
			FunctionDictionary<FunctionStatementBlock> dict = getNamespaces().get(namespaceKey);
			
			sb.append("FUNCTIONS = ");
			for (FunctionStatementBlock fsb : dict.getFunctions().values()){
				sb.append(fsb);
				sb.append(", ");
			}
			sb.append("\n");
			sb.append("********************************** \n");
		
		}
		
		sb.append("******** MAIN SCRIPT BODY ******** \n");
		for (StatementBlock b : _blocks){
			sb.append(b);
			sb.append("\n");
		}
		sb.append("********************************** \n");
		return sb.toString();
	}
	
	public static String constructFunctionKey(String fnamespace, String fname) {
		String sfnamespace = fnamespace == null ?
			DMLProgram.DEFAULT_NAMESPACE : fnamespace;
		return sfnamespace + Program.KEY_DELIM + fname;
	}
	
	public static String[] splitFunctionKey(String fkey) {
		return fkey.split(Program.KEY_DELIM);
	}
}

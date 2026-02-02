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

package org.apache.sysds.hops.fedplanner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

public final class FederatedRefedPolicy {
	private static final long DEFAULT_SBID = -1L;
	private static final Log LOG = LogFactory.getLog(FederatedRefedPolicy.class.getName());
	private static final boolean ENABLE_TRANSREAD_DEBUG =
		Boolean.parseBoolean(System.getProperty("sysds.fedplanner.transread.debug", "false"));
	private static final Map<Long, AnchorKey> CPFOUT_ANCHOR_CACHE = new ConcurrentHashMap<>();
	private static final ThreadLocal<java.util.Map<String, List<DataOp>>> GLOBAL_TWRITE_CACHE =
		ThreadLocal.withInitial(java.util.HashMap::new);
	private static final ThreadLocal<java.util.Map<Long, Long>> HOP_SBID_CACHE =
		ThreadLocal.withInitial(java.util.HashMap::new);
	private static final ThreadLocal<Set<String>> LOCAL_TR_VARS = new ThreadLocal<>();
	static {
		if (ENABLE_TRANSREAD_DEBUG)
			System.out.println("[TransReadRefedDebug] enabled");
	}

	private static void clearCpfoutAnchorCache() {
		CPFOUT_ANCHOR_CACHE.clear();
	}

	private static void clearGlobalTWriteCache() {
		GLOBAL_TWRITE_CACHE.get().clear();
		HOP_SBID_CACHE.get().clear();
	}

	private FederatedRefedPolicy() {
	}

	public static void registerFromProgram(DMLProgram prog) {
		registerFromProgram(prog, null);
	}

	public static void registerFromProgram(DMLProgram prog, java.util.Map<Long, FType> fTypeMap) {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		clearCpfoutAnchorCache();
		clearGlobalTWriteCache();
		FederatedPlannerUtils.clearFedAnchorKeys();
		if (prog == null)
			return;
		AnchorSelection programAnchor = buildGlobalAnchorForProgram(prog, fTypeMap);
		for (StatementBlock sb : prog.getStatementBlocks())
			registerFromStatementBlock(sb, fTypeMap, programAnchor, false);
		for (String namespaceKey : prog.getNamespaces().keySet()) {
			for (String fname : prog.getFunctionStatementBlocks(namespaceKey).keySet()) {
				FunctionStatementBlock fsb = prog.getFunctionStatementBlock(namespaceKey, fname);
				AnchorSelection functionAnchor = buildGlobalAnchorForFunction(fsb, fTypeMap);
				registerFromStatementBlock(fsb, fTypeMap, functionAnchor, true);
			}
		}
	}

	public static void registerFromFunction(FunctionStatementBlock function) {
		registerFromFunction(function, null);
	}

	public static void registerFromFunction(FunctionStatementBlock function, java.util.Map<Long, FType> fTypeMap) {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		clearCpfoutAnchorCache();
		FederatedPlannerUtils.clearFedAnchorKeys();
		clearGlobalTWriteCache();
		if (function == null)
			return;
		FunctionStatement fstmt = (FunctionStatement) function.getStatement(0);
		if (fstmt == null)
			return;
		AnchorSelection functionAnchor = buildGlobalAnchorForFunction(function, fTypeMap);
		for (StatementBlock inner : fstmt.getBody())
			registerFromStatementBlock(inner, fTypeMap, functionAnchor, true);
	}

	public static void registerFromHops(List<Hop> roots, boolean clearRegistry) {
		registerFromHops(roots, clearRegistry, null);
	}

	public static void registerFromHops(List<Hop> roots, boolean clearRegistry, java.util.Map<Long, FType> fTypeMap) {
		registerFromHops(roots, clearRegistry, fTypeMap, DEFAULT_SBID);
	}

	public static void registerFromHops(List<Hop> roots, boolean clearRegistry,
			java.util.Map<Long, FType> fTypeMap, long sbId) {
		registerFromHops(roots, clearRegistry, fTypeMap, sbId, null, null);
	}

	public static void registerFromHops(List<Hop> roots, boolean clearRegistry,
			java.util.Map<Long, FType> fTypeMap, long sbId,
			java.util.Map<String, String> runtimeSignatures,
			java.util.Map<String, FType> runtimeTypes) {
		registerFromHopsInternal(roots, clearRegistry, fTypeMap, sbId, runtimeSignatures, runtimeTypes, null, false);
	}

	private static void registerFromHopsInternal(List<Hop> roots, boolean clearRegistry,
			java.util.Map<Long, FType> fTypeMap, long sbId,
			java.util.Map<String, String> runtimeSignatures,
			java.util.Map<String, FType> runtimeTypes,
			AnchorSelection fallbackAnchor, boolean conditionalContext) {
			if (clearRegistry)
				FederatedRefedRegistry.clear();
			if (clearRegistry)
				FederatedFoutMaterializeRegistry.clear();
			if (clearRegistry)
				clearCpfoutAnchorCache();
			// In the recompiler path we get the authoritative runtime federation state (signatures/types)
			// from the current symbol table. Reset variable anchor keys to avoid staleness across recompilations.
			boolean runtimeContext = (runtimeSignatures != null && !runtimeSignatures.isEmpty());
			if (clearRegistry && runtimeContext)
				FederatedPlannerUtils.clearFedAnchorKeys();
			if (roots == null || roots.isEmpty())
				return;

			// Make runtime federated variables visible to anchor selection and "runtime federated input"
			// checks by registering a stable signature-based anchor key per variable.
			if (runtimeSignatures != null && !runtimeSignatures.isEmpty()) {
				for (java.util.Map.Entry<String, String> entry : runtimeSignatures.entrySet()) {
					String varName = entry.getKey();
					String sig = entry.getValue();
					if (varName == null || varName.isEmpty() || sig == null || sig.isEmpty())
						continue;
					FType fType = (runtimeTypes != null) ? runtimeTypes.get(varName) : null;
					AnchorKey key = buildAnchorKeyFromSignature(sig, fType);
					if (key != null && key.value instanceof String)
						FederatedPlannerUtils.registerFedAnchorKey(varName, (String) key.value);
				}
			}

			List<Hop> all = collectAllHops(roots);
			if (all != null && !all.isEmpty()) {
				java.util.Map<String, List<DataOp>> globalWrites = GLOBAL_TWRITE_CACHE.get();
				java.util.Map<Long, Long> hopSbIds = HOP_SBID_CACHE.get();
				for (Hop hop : all) {
					if (!(hop instanceof DataOp))
						continue;
					DataOp dataOp = (DataOp) hop;
					if (dataOp.getOp() != OpOpData.TRANSIENTWRITE)
						continue;
					String name = dataOp.getName();
					if (name == null || name.isEmpty())
						continue;
					globalWrites.computeIfAbsent(name, k -> new ArrayList<>()).add(dataOp);
				}
				for (Hop hop : all) {
					if (hop != null && hop.getHopID() > 0 && !hopSbIds.containsKey(hop.getHopID()))
						hopSbIds.put(hop.getHopID(), sbId);
				}
			}
			if (runtimeSignatures != null && !runtimeSignatures.isEmpty()) {
				for (Hop hop : all) {
					if (!(hop instanceof DataOp))
					continue;
				DataOp dataOp = (DataOp) hop;
				if (dataOp.getOp() != OpOpData.TRANSIENTREAD)
					continue;
				String name = dataOp.getName();
				if (name == null || name.isEmpty())
					continue;
				boolean runtimeFed = runtimeSignatures.containsKey(name)
					|| FederatedPlannerUtils.isFedInitVar(name)
					|| FederatedPlannerUtils.getFedAnchorKey(name) != null;
				ExecType planned = getPlannedExecType(hop);
				if (runtimeFed) {
					if (planned != ExecType.FED) {
						hop.setForcedExecType(ExecType.FED);
						hop.setFederatedOutput(FederatedOutput.FOUT);
					}
					if (fTypeMap != null && runtimeTypes != null) {
						FType runtimeType = runtimeTypes.get(name);
						if (runtimeType != null)
							fTypeMap.put(hop.getHopID(), runtimeType);
					}
				}
				else if (planned == ExecType.FED) {
					hop.setForcedExecType(ExecType.CP);
					hop.setFederatedOutput(FederatedOutput.LOUT);
					if (fTypeMap != null)
						fTypeMap.remove(hop.getHopID());
				}
			}
		}

		// Clear stale anchor keys for variables that are locally overwritten in this block,
		// so subsequent anchor selection does not treat them as federated.
		if (all != null && !all.isEmpty()) {
			for (Hop hop : all) {
				if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTWRITE) {
					DataOp tWrite = (DataOp) hop;
					if (tWrite.getFederatedOutput() != FederatedOutput.FOUT) {
						String varName = tWrite.getName();
						if (varName != null && !varName.isEmpty())
							FederatedPlannerUtils.removeFedAnchorKey(varName);
					}
				}
			}
		}

		if (all != null && !all.isEmpty()) {
			if (!runtimeContext)
				propagateTransientFederatedTypes(all, fTypeMap);
			promoteTransientReadsFromAnchors(all, fTypeMap);
		}

		AnchorSelection blockAnchor = buildBlockAnchorSelection(all, fTypeMap, runtimeSignatures);
		if (blockAnchor == null) {
			AnchorSelection synthetic = buildSyntheticAnchorSelection(all, fTypeMap, runtimeSignatures, runtimeTypes);
			if (synthetic != null) {
				all.add(synthetic.anchorHop);
				roots.add(synthetic.anchorHop);
				blockAnchor = synthetic;
			} else if (fallbackAnchor != null) {
				// Resolve the fallback anchor to a hop that is actually present in this block's hop graph.
				// Otherwise, later lop insertion will not find an anchor lop and silently skip CP->FOUT/refed.
				AnchorSelection resolved = resolveFallbackAnchor(all, fTypeMap, fallbackAnchor);
				if (resolved != null) {
					if (!all.contains(resolved.anchorHop)) {
						all.add(resolved.anchorHop);
						roots.add(resolved.anchorHop);
					}
					blockAnchor = resolved;
				}
			}
			if (blockAnchor == null) {
				AnchorSelection resolved = buildFederatedAnchorFromHops(all, fTypeMap);
				if (resolved != null) {
					if (!all.contains(resolved.anchorHop)) {
						all.add(resolved.anchorHop);
						roots.add(resolved.anchorHop);
					}
					blockAnchor = resolved;
				}
			}
		}
		if (fTypeMap != null) {
			for (Hop hop : all) {
				if (!(hop instanceof DataOp))
					continue;
				DataOp dataOp = (DataOp) hop;
				if (dataOp.getOp() != OpOpData.TRANSIENTREAD)
					continue;
				if (fTypeMap.containsKey(hop.getHopID()))
					continue;
				FType anchored = getFTypeFromAnchorKey(FederatedPlannerUtils.getFedAnchorKey(dataOp.getName()));
				if (anchored != null)
					fTypeMap.put(hop.getHopID(), anchored);
			}
		}
		for (Hop hop : all) {
			if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTWRITE) {
				// TransientWrite hops assign their input into the symbol table. If the planner
				// requires a federated value to be written (FOUT), we must materialize the
				// computed input *before* the write and wire the federated result into the
				// TWrite. This is handled safely by the fed_fout materialization path during
				// lop insertion (see Dag.insertFoutMaterializeLops special handling).
				if (hop.getFederatedOutput() == FederatedOutput.FOUT) {
					List<Hop> inputs = hop.getInput();
					Hop input = (inputs != null && !inputs.isEmpty()) ? inputs.get(0) : null;
						if (input != null && input.getDataType() != null && input.getDataType().isMatrix()) {
							ExecType inExec = getPlannedExecType(input);
							if (inExec == null)
								inExec = ExecType.CP;
							boolean inputLocal = inExec == ExecType.CP || (inExec == ExecType.FED && input.hasLocalOutput());
							if (inputLocal) {
								AnchorSelection selection = selectAnchorWithinBlock(hop, fTypeMap, true, false, blockAnchor);
								if (selection == null || selection.key == null) {
								hop.setFederatedOutput(FederatedOutput.LOUT);
								if (hop.getForcedExecType() == ExecType.FED)
									hop.setForcedExecType(ExecType.CP);
								if (fTypeMap != null)
									fTypeMap.remove(hop.getHopID());
							} else {
								// Register materialization for the TWrite itself (not the input hop),
								// so the lop compiler can wire the federated value into the write.
								registerCpfoutWithSelection(hop, fTypeMap, sbId, selection);
							}
						}
					}
				}
				continue;
			}
			ExecType exec = getPlannedExecType(hop);
			if (exec == null)
				exec = ExecType.CP;
			boolean localOutput = exec == ExecType.CP
					|| (exec == ExecType.FED && (hop.hasLocalOutput() || hop.isFederatedOutputDerived()));
			if (localOutput && hop.getDataType().isMatrix()
				&& (hop.getFederatedOutput() == FederatedOutput.FOUT
					|| requiresCpfoutForFedParents(hop, fTypeMap))) {
				if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTREAD) {
					if (ENABLE_TRANSREAD_DEBUG && "Y".equals(hop.getName())) {
						System.out.println("[TransReadRefedDebug] hop=" + hop.getHopID()
							+ " exec=" + exec
							+ " fout=" + hop.getFederatedOutput()
							+ " localOutput=" + localOutput
							+ " needsCpfout=" + requiresCpfoutForFedParents(hop, fTypeMap));
					}
					// TRead must not use CP->FOUT directly; promote matching TWrite instead.
					if (promoteTransientReadViaTWrite((DataOp) hop, all, fTypeMap, sbId, blockAnchor))
						continue;
					if (exec == ExecType.CP && hop.getFederatedOutput() == FederatedOutput.FOUT) {
						hop.setFederatedOutput(FederatedOutput.LOUT);
						if (fTypeMap != null)
							fTypeMap.remove(hop.getHopID());
					}
					continue;
				}
				if (registerCpfoutViaTransientWrite(hop, fTypeMap, sbId, blockAnchor))
					continue;
				if (!canGenerateCpfoutCandidate(hop, fTypeMap, blockAnchor)) {
					if (hop.getFederatedOutput() == FederatedOutput.FOUT) {
						hop.setFederatedOutput(FederatedOutput.LOUT);
						if (fTypeMap != null)
							fTypeMap.remove(hop.getHopID());
					}
					continue;
				}
				try {
					validateAndRegister(hop, fTypeMap, sbId, blockAnchor);
				}
				catch (RuntimeException ex) {
					boolean isFout = (hop != null && hop.getFederatedOutput() == FederatedOutput.FOUT);
					boolean derivedFedFout = (hop != null && hop.isFederatedOutputDerived());
					String mode = (exec == ExecType.FED && isFout && derivedFedFout)
						? "FED/FOUT(derived via refed)"
						: (exec == ExecType.CP && isFout) ? "CP->FOUT" : "CPFOUT";
					LOG.error("Refed candidate failed (" + mode + "): hopID=" + (hop != null ? hop.getHopID() : -1)
						+ " ident=" + (hop != null ? System.identityHashCode(hop) : -1)
						+ " name=" + (hop != null ? hop.getName() : "null")
						+ " op=" + (hop != null ? hop.getOpString() : "null")
						+ " dataType=" + (hop != null ? hop.getDataType() : null)
						+ " plannedExecType=" + exec
						+ " forcedExecType=" + (hop != null ? hop.getForcedExecType() : null)
						+ " execType=" + (hop != null ? hop.getExecType() : null)
						+ " federatedOutput=" + (hop != null ? hop.getFederatedOutput() : null)
						+ " federatedOutputDerived=" + derivedFedFout
						+ " hasLocalOutput=" + (hop != null && hop.hasLocalOutput())
						+ " sbId=" + sbId, ex);
					throw ex;
				}
			}
		}
		// Final cleanup: prevent illegal CP/FOUT on transient reads by promoting matching TWrite
		// or demoting to LOUT (so FED parents can be demoted if needed).
		for (Hop hop : all) {
			if (!(hop instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() != OpOpData.TRANSIENTREAD)
				continue;
			ExecType exec = getPlannedExecType(hop);
			if (exec == null)
				exec = ExecType.CP;
			if (exec == ExecType.CP && hop.getFederatedOutput() == FederatedOutput.FOUT) {
				if (ENABLE_TRANSREAD_DEBUG && "Y".equals(hop.getName())) {
					System.out.println("[TransReadRefedDebug] cleanup hop=" + hop.getHopID()
						+ " trying promote via TWrite");
				}
				if (promoteTransientReadViaTWrite((DataOp) hop, all, fTypeMap, sbId, blockAnchor))
					continue;
				hop.setFederatedOutput(FederatedOutput.LOUT);
				if (fTypeMap != null)
					fTypeMap.remove(hop.getHopID());
			}
		}
		if (!runtimeContext) {
			for (Hop hop : all) {
				if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTWRITE) {
					// Track transient writes that carry federated anchors so later TRead hops can
					// be treated as federated inputs in subsequent blocks.
					registerTransientWriteAnchor((DataOp) hop, fTypeMap, blockAnchor, sbId, conditionalContext);
				}
			}
		}
		enforceFederatedInputs(all, fTypeMap, sbId, blockAnchor);
		boolean pruned = false;
		int prunePass = 0;
		do {
			pruned = pruneInvalidCpfoutAnchors(all, sbId);
			if (pruned)
				enforceFederatedInputs(all, fTypeMap, sbId, blockAnchor);
			prunePass++;
		} while (pruned && prunePass < 3);
		// registerTransientWriteAnchor moved earlier (before enforceFederatedInputs)
	}

	public static void registerFoutMaterializeCandidate(Hop hop, java.util.Map<Long, FType> fTypeMap, long sbId) {
		if (hop == null)
			return;
		List<Hop> roots = new ArrayList<>();
		roots.add(hop);
		registerFoutMaterializeCandidates(roots, fTypeMap, sbId);
	}

	public static void registerFoutMaterializeCandidates(List<Hop> roots, java.util.Map<Long, FType> fTypeMap, long sbId) {
		if (roots == null || roots.isEmpty())
			return;
		Set<Hop> visited = new HashSet<>();
		Deque<Hop> queue = new ArrayDeque<>();
		for (Hop root : roots)
			if (root != null)
				queue.add(root);
		List<Hop> all = new ArrayList<>();

		while (!queue.isEmpty()) {
			Hop hop = queue.poll();
			if (!visited.add(hop))
				continue;
			all.add(hop);
			for (Hop in : hop.getInput())
				queue.add(in);
		}

		AnchorSelection blockAnchor = buildBlockAnchorSelection(all, fTypeMap, null);
		for (Hop root : roots) {
			if (root == null)
				continue;
			validateAndRegister(root, fTypeMap, sbId, blockAnchor);
		}
	}

	private static boolean registerCpfoutViaTransientWrite(Hop hop, java.util.Map<Long, FType> fTypeMap,
			long sbId, AnchorSelection blockAnchor) {
		if (hop == null || hop.getParent() == null || hop.getParent().isEmpty())
			return false;
		FType hopType = getKnownFType(hop, fTypeMap);
		if (hopType == null)
			return false;
		for (Hop parent : hop.getParent()) {
			if (!(parent instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) parent;
			if (dataOp.getOp() != OpOpData.TRANSIENTWRITE)
				continue;
			if (isRecompileRegion(parent))
				continue;
			List<Hop> inputs = parent.getInput();
			if (inputs == null || inputs.isEmpty() || inputs.get(0) != hop)
				continue;
			AnchorSelection selection = selectAnchorWithinBlock(parent, fTypeMap, true, false, blockAnchor);
			if (selection == null || selection.key == null)
				continue;
			parent.setFederatedOutput(FederatedOutput.FOUT);
			parent.setForcedExecType(ExecType.FED);
			if (fTypeMap != null)
				fTypeMap.put(parent.getHopID(), hopType);
			registerCpfoutWithSelection(parent, fTypeMap, sbId, selection);
			return true;
		}
		return false;
	}

	private static boolean promoteTransientReadViaTWrite(DataOp tRead, List<Hop> all,
			java.util.Map<Long, FType> fTypeMap, long sbId, AnchorSelection blockAnchor) {
		if (tRead == null || tRead.getOp() != OpOpData.TRANSIENTREAD)
			return false;
		if (all == null || all.isEmpty())
			return false;
		String name = tRead.getName();
		if (name == null || name.isEmpty())
			return false;
		List<DataOp> writes = new ArrayList<>();
		for (Hop hop : all) {
			if (!(hop instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() != OpOpData.TRANSIENTWRITE)
				continue;
			String wName = dataOp.getName();
			if (name.equals(wName))
				writes.add(dataOp);
		}
		if (writes.isEmpty()) {
			java.util.Map<String, List<DataOp>> globalWrites = GLOBAL_TWRITE_CACHE.get();
			List<DataOp> cached = globalWrites.get(name);
			if (cached != null && !cached.isEmpty())
				writes.addAll(cached);
		}
		DataOp tWrite = selectMatchingTWrite(writes, tRead);
		if (tWrite == null)
			return false;
		if (ENABLE_TRANSREAD_DEBUG && "Y".equals(tRead.getName())) {
			System.out.println("[TransReadRefedDebug] match TWrite hop=" + tWrite.getHopID()
				+ " line=" + tWrite.getBeginLine());
		}
		if (isRecompileRegion(tWrite))
			return false;

		boolean tWriteFed = tWrite.hasFederatedOutput();
		ExecType wExec = getPlannedExecType(tWrite);
		if (wExec == ExecType.FED && !tWrite.hasLocalOutput())
			tWriteFed = true;

		if (!tWriteFed) {
			List<Hop> inputs = tWrite.getInput();
			Hop input = (inputs != null && !inputs.isEmpty()) ? inputs.get(0) : null;
			if (input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				return false;
			AnchorSelection selection = selectAnchorWithinBlock(tWrite, fTypeMap, true, false, blockAnchor);
			if (selection == null || selection.key == null)
				return false;
			if (ENABLE_TRANSREAD_DEBUG && "Y".equals(tRead.getName())) {
				System.out.println("[TransReadRefedDebug] promote TWrite hop=" + tWrite.getHopID()
					+ " anchor=" + selection.key.value);
			}
			tWrite.setFederatedOutput(FederatedOutput.FOUT);
			tWrite.setForcedExecType(ExecType.FED);
			if (fTypeMap != null) {
				FType fType = getKnownFType(input, fTypeMap);
				if (fType != null)
					fTypeMap.put(tWrite.getHopID(), fType);
			}
			long tWriteSbId = resolveHopSbId(tWrite.getHopID(), sbId);
			registerCpfoutWithSelection(tWrite, fTypeMap, tWriteSbId, selection);
		}

		tRead.setForcedExecType(ExecType.FED);
		tRead.setFederatedOutput(FederatedOutput.FOUT);
		if (fTypeMap != null) {
			FType fType = fTypeMap.get(tWrite.getHopID());
			if (fType == null && tWrite.getInput() != null && !tWrite.getInput().isEmpty()) {
				Hop input = tWrite.getInput().get(0);
				if (input != null)
					fType = fTypeMap.get(input.getHopID());
			}
			if (fType != null)
				fTypeMap.put(tRead.getHopID(), fType);
		}
		return true;
	}

	private static void propagateTransientFederatedTypes(List<Hop> hops, java.util.Map<Long, FType> fTypeMap) {
		Map<String, List<DataOp>> tWrites = new java.util.HashMap<>();
		Map<String, List<DataOp>> tReads = new java.util.HashMap<>();
		for (Hop hop : hops) {
			if (!(hop instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hop;
			String name = dataOp.getName();
			if (name == null || name.isEmpty())
				continue;
			OpOpData op = dataOp.getOp();
			if (op == OpOpData.TRANSIENTWRITE) {
				tWrites.computeIfAbsent(name, k -> new ArrayList<>()).add(dataOp);
			}
			else if (op == OpOpData.TRANSIENTREAD) {
				tReads.computeIfAbsent(name, k -> new ArrayList<>()).add(dataOp);
			}
		}
		if (tWrites.isEmpty() || tReads.isEmpty())
			return;
		for (Map.Entry<String, List<DataOp>> entry : tReads.entrySet()) {
			String name = entry.getKey();
			List<DataOp> reads = entry.getValue();
			List<DataOp> writes = tWrites.get(name);
			if (reads == null || reads.isEmpty() || writes == null || writes.isEmpty())
				continue;
			for (DataOp tRead : reads) {
				DataOp tWrite = selectMatchingTWrite(writes, tRead);
				if (tWrite == null)
					continue;
				ExecType exec = getPlannedExecType(tWrite);
				FType fType = null;
				if (fTypeMap != null)
					fType = fTypeMap.get(tWrite.getHopID());
				if (fType == null && tWrite.getInput() != null && !tWrite.getInput().isEmpty()) {
					Hop input = tWrite.getInput().get(0);
					if (input != null && fTypeMap != null)
						fType = fTypeMap.get(input.getHopID());
				}
				boolean tWriteFed = exec == ExecType.FED && !tWrite.hasLocalOutput();
				if (!tWriteFed) {
					Hop input = tWrite.getInput() != null && !tWrite.getInput().isEmpty()
						? tWrite.getInput().get(0)
						: null;
					if (input != null && isRuntimeFederatedInput(input, null, null))
						tWriteFed = true;
				}
				if (!tWriteFed)
					continue;
				tRead.setForcedExecType(ExecType.FED);
				tRead.setFederatedOutput(FederatedOutput.FOUT);
				if (fTypeMap != null && fType != null)
					fTypeMap.put(tRead.getHopID(), fType);
			}
		}
	}

	private static DataOp selectMatchingTWrite(List<DataOp> writes, DataOp tRead) {
		if (writes == null || writes.isEmpty() || tRead == null)
			return null;
		if (writes.size() == 1)
			return writes.get(0);
		int readLine = tRead.getBeginLine();
		if (readLine <= 0)
			return null;
		DataOp best = null;
		int bestLine = -1;
		for (DataOp write : writes) {
			if (write == null)
				continue;
			int writeLine = write.getBeginLine();
			if (writeLine <= 0 || writeLine > readLine)
				continue;
			if (writeLine > bestLine) {
				bestLine = writeLine;
				best = write;
			}
		}
		return best;
	}

	private static void promoteTransientReadsFromAnchors(List<Hop> hops, java.util.Map<Long, FType> fTypeMap) {
		if (hops == null || hops.isEmpty())
			return;
		// Build local TWrite index so we only promote reads that actually refer to
		// a federated transient write in this block (or have no local write).
		Map<String, List<DataOp>> tWrites = new java.util.HashMap<>();
		for (Hop hop : hops) {
			if (!(hop instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() != OpOpData.TRANSIENTWRITE)
				continue;
			String name = dataOp.getName();
			if (name == null || name.isEmpty())
				continue;
			tWrites.computeIfAbsent(name, k -> new ArrayList<>()).add(dataOp);
		}
		for (Hop hop : hops) {
			if (!(hop instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() != OpOpData.TRANSIENTREAD)
				continue;
			String name = dataOp.getName();
			if (name == null || name.isEmpty())
				continue;
			// If this read is dominated by a local (non-federated) TWrite in the same block,
			// do NOT promote it to FED even if a global anchor exists for the variable.
			List<DataOp> writes = tWrites.get(name);
			DataOp tWrite = selectMatchingTWrite(writes, dataOp);
			if (tWrite != null) {
				ExecType wExec = getPlannedExecType(tWrite);
				boolean wFed = tWrite.hasFederatedOutput()
					|| (wExec == ExecType.FED && !tWrite.hasLocalOutput());
				if (!wFed)
					continue;
			}
			String anchorKey = FederatedPlannerUtils.getFedAnchorKey(name);
			boolean isFedInit = FederatedPlannerUtils.isFedInitVar(name);
			if (anchorKey != null && isVarAnchorKey(anchorKey))
				anchorKey = null;
			if (!isFedInit && anchorKey == null)
				continue;
			dataOp.setForcedExecType(ExecType.FED);
			dataOp.setFederatedOutput(FederatedOutput.FOUT);
			if (fTypeMap != null && !fTypeMap.containsKey(dataOp.getHopID())) {
				FType fType = null;
				if (fType == null && tWrite != null) {
					fType = fTypeMap != null ? fTypeMap.get(tWrite.getHopID()) : null;
					if (fType == null && tWrite.getInput() != null && !tWrite.getInput().isEmpty()) {
						Hop in = tWrite.getInput().get(0);
						if (in != null && fTypeMap != null)
							fType = fTypeMap.get(in.getHopID());
					}
					if (fType == null) {
						FType axis = FederatedPlannerUtils.getVectorAxis(tWrite);
						if (axis != null)
							fType = axis;
					}
				}
				if (anchorKey != null)
					fType = getFTypeFromAnchorKey(anchorKey);
				if (fType == null && isFedInit)
					fType = FederatedPlannerUtils.getFedInitFType(name);
				if (fType != null)
					fTypeMap.put(dataOp.getHopID(), fType);
			}
		}
	}

	private static void enforceFederatedInputs(List<Hop> all, java.util.Map<Long, FType> fTypeMap, long sbId,
			AnchorSelection blockAnchor) {
		if (all == null || all.isEmpty())
			return;
		for (Hop hop : all) {
			if (hop == null)
				continue;
			if (ENABLE_TRANSREAD_DEBUG && hop instanceof DataOp
					&& ((DataOp) hop).getOp() == OpOpData.TRANSIENTREAD
					&& "Y".equals(((DataOp) hop).getName())) {
				System.out.println("[TransReadRefedDebug] visit hop=" + hop.getHopID()
					+ " exec=" + getPlannedExecType(hop)
					+ " fout=" + hop.getFederatedOutput());
			}
			ExecType exec = getPlannedExecType(hop);
			if (exec != ExecType.FED)
				continue;
			if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTWRITE)
				continue;
			String opString = hop.getOpString();
			if (opString != null && opString.startsWith("TWrite"))
				continue;
			if (isFederatedInitDataOp(hop) || isFederatedSourceOp(hop, fTypeMap))
				continue;
			if (!ensureRequiredFederatedInputs(hop, fTypeMap, sbId, blockAnchor)) {
				if (tryDemoteFederatedHop(hop, fTypeMap, sbId, blockAnchor))
					continue;
				throw new DMLRuntimeException("FED hop has no federated inputs and no CP->FOUT candidate. "
					+ "hopID=" + hop.getHopID() + " op=" + hop.getOpString()
					+ " name=" + hop.getName() + " inputs=" + describeInputs(hop));
			}
		}
	}

	private static boolean pruneInvalidCpfoutAnchors(List<Hop> all, long sbId) {
		if (all == null || all.isEmpty())
			return false;
		java.util.Map<Long, Hop> hopById = new java.util.HashMap<>();
		for (Hop hop : all) {
			if (hop != null)
				hopById.put(hop.getHopID(), hop);
		}
		java.util.Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> materialize =
			FederatedFoutMaterializeRegistry.snapshot(sbId);
		java.util.Map<Long, FederatedRefedRegistry.AnchorSpec> refed = FederatedRefedRegistry.snapshot(sbId);
		boolean changed = false;

		for (java.util.Map.Entry<Long, FederatedRefedRegistry.AnchorSpec> entry : refed.entrySet()) {
			Hop localHop = hopById.get(entry.getKey());
			if (localHop != null && isRuntimeFederatedInput(localHop, null, null)) {
				FederatedRefedRegistry.remove(sbId, entry.getKey());
				CPFOUT_ANCHOR_CACHE.remove(entry.getKey());
				changed = true;
				continue;
			}
			FederatedRefedRegistry.AnchorSpec spec = entry.getValue();
			long anchorHopId = spec.getAnchorHopId();
			Hop anchorHop = hopById.get(anchorHopId);
			if ((anchorHop == null || !isRuntimeFederatedInput(anchorHop, null, null)) && spec.getAnchorKey() == null) {
				FederatedRefedRegistry.remove(sbId, entry.getKey());
				CPFOUT_ANCHOR_CACHE.remove(entry.getKey());
				changed = true;
			}
		}
		for (java.util.Map.Entry<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> entry : materialize.entrySet()) {
			Hop localHop = hopById.get(entry.getKey());
			if (localHop != null && isRuntimeFederatedInput(localHop, null, null)) {
				FederatedFoutMaterializeRegistry.remove(sbId, entry.getKey());
				CPFOUT_ANCHOR_CACHE.remove(entry.getKey());
				changed = true;
				continue;
			}
			FederatedFoutMaterializeRegistry.MaterializeSpec spec = entry.getValue();
			long anchorHopId = spec.getAnchorHopId();
			Hop anchorHop = hopById.get(anchorHopId);
			if ((anchorHop == null || !isRuntimeFederatedInput(anchorHop, null, null)) && spec.getAnchorKey() == null) {
				FederatedFoutMaterializeRegistry.remove(sbId, entry.getKey());
				CPFOUT_ANCHOR_CACHE.remove(entry.getKey());
				changed = true;
			}
		}
		return changed;
	}

	private static boolean tryDemoteFederatedHop(Hop hop, java.util.Map<Long, FType> fTypeMap, long sbId,
			AnchorSelection blockAnchor) {
		if (hop == null)
			return false;
		if (hop.getForcedExecType() == ExecType.FED && LOG.isDebugEnabled())
			LOG.debug("Demoting forced-FED hop due to missing federated inputs: hopID=" + hop.getHopID()
				+ " op=" + hop.getOpString() + " name=" + hop.getName());
		hop.setForcedExecType(ExecType.CP);
		hop.setFederatedOutput(FederatedOutput.LOUT);
		if (fTypeMap != null)
			fTypeMap.remove(hop.getHopID());
		if (!requiresCpfoutForFedParents(hop, fTypeMap))
			return true;
		if (!canGenerateCpfoutCandidate(hop, fTypeMap, blockAnchor))
			return false;
		validateAndRegister(hop, fTypeMap, sbId, blockAnchor);
		return true;
	}

	private static boolean ensureRequiredFederatedInputs(Hop hop, java.util.Map<Long, FType> fTypeMap, long sbId,
			AnchorSelection blockAnchor) {
		if (hop == null || hop.getInput() == null)
			return true;
		java.util.Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> materialize =
			FederatedFoutMaterializeRegistry.snapshot(sbId);
		java.util.Map<Long, FederatedRefedRegistry.AnchorSpec> refed = FederatedRefedRegistry.snapshot(sbId);
		AnchorKey globalAnchorKey = selectGlobalAnchorKey(fTypeMap);

		AnchorSelection requiredAnchor = null;
		AnchorSelection consumerAnchor = null;
		AnchorSelection optionalAnchor = null;
		boolean hasRequiredMatrix = false;
		List<Integer> requiredIndices = new ArrayList<>();

		for (int i = 0; i < hop.getInput().size(); i++) {
			Hop input = hop.getInput().get(i);
			if (input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				continue;
			InputRequirement req = resolveTargetRequirement(hop, input, i, fTypeMap, blockAnchor);
			boolean runtimeFed = isRuntimeFederatedInput(input, materialize, refed);
			boolean sourceFed = runtimeFed && isRuntimeFederatedInput(input, null, null);
			if (req == InputRequirement.OPTIONAL) {
				if (runtimeFed) {
					// Do not use planned CP->FOUT candidates as anchors; only accept true runtime
					// federated sources here to avoid cyclic anchoring chains.
					if (sourceFed) {
						if (optionalAnchor == null) {
							AnchorKey key = buildAnchorKey(input, fTypeMap);
							optionalAnchor = new AnchorSelection(key, input);
						}
						else if (optionalAnchor.key == null) {
							AnchorKey key = buildAnchorKey(input, fTypeMap);
							if (key != null)
								optionalAnchor = new AnchorSelection(key, input);
						}
					}
					continue;
				}
				// Optional-but-local inputs still need federation (e.g., broadcast) for FED exec.
			}
			hasRequiredMatrix = true;
			if (!runtimeFed)
				requiredIndices.add(i);
			if (sourceFed) {
				AnchorKey key = buildAnchorKey(input, fTypeMap);
				if (key != null) {
					if (requiredAnchor == null || requiredAnchor.key == null)
						requiredAnchor = new AnchorSelection(key, input);
					else if (!anchorsCompatible(requiredAnchor.key, key))
						return false;
				} else if (requiredAnchor == null) {
					requiredAnchor = new AnchorSelection(null, input);
				}
			}
		}

		if (!hasRequiredMatrix)
			return true;

		if (!requiredIndices.isEmpty()) {
			// Prefer an anchor with a concrete key if we need to upload any required local inputs.
			if ((requiredAnchor == null || requiredAnchor.key == null) && optionalAnchor != null
				&& optionalAnchor.key != null) {
				requiredAnchor = optionalAnchor;
			}
			if (requiredAnchor == null || requiredAnchor.key == null)
				consumerAnchor = selectAnchorWithinBlock(hop, fTypeMap, true, false, blockAnchor);
		}

			for (int idx : requiredIndices) {
				Hop input = hop.getInput().get(idx);
				AnchorSelection selection = null;
				if (requiredAnchor != null && requiredAnchor.key != null)
					selection = requiredAnchor;
				else if (consumerAnchor != null)
					selection = consumerAnchor;
				else
					selection = selectAnchorWithinBlock(input, fTypeMap, true, false, blockAnchor);
				if (selection == null && globalAnchorKey != null)
					selection = new AnchorSelection(globalAnchorKey, null);
				if (selection == null || selection.key == null)
					return false;
				if (requiredAnchor == null || requiredAnchor.key == null)
					requiredAnchor = selection;
				else if (!anchorsCompatible(requiredAnchor.key, selection.key))
				return false;
			try {
				validateAndRegisterRequired(input, fTypeMap, sbId, selection);
			}
			catch (DMLRuntimeException ex) {
				return false;
			}
			materialize = FederatedFoutMaterializeRegistry.snapshot(sbId);
			refed = FederatedRefedRegistry.snapshot(sbId);
		}

		for (int i = 0; i < hop.getInput().size(); i++) {
			Hop input = hop.getInput().get(i);
			if (input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				continue;
			InputRequirement req = resolveTargetRequirement(hop, input, i, fTypeMap, blockAnchor);
			if (req == InputRequirement.OPTIONAL)
				continue;
			if (!isRuntimeFederatedInput(input, materialize, refed))
				return false;
		}
		return true;
	}

	private static InputRequirement resolveTargetRequirement(Hop parent, Hop input, int index,
			java.util.Map<Long, FType> fTypeMap, AnchorSelection blockAnchor) {
		InputRequirement req = classifyTargetRequirement(parent, input, index, fTypeMap);
		if (req == InputRequirement.AMBIGUOUS) {
			if (FederatedPlannerUtils.isScalarLikeMatrix(input)
				|| shouldRelaxAmbiguousTargetRequirement(parent, input, index, fTypeMap)) {
				req = InputRequirement.REQUIRED;
			}
			else if (blockAnchor != null && !hasFederatedInput(parent, input, fTypeMap)) {
				req = InputRequirement.OPTIONAL;
			}
			else {
				req = InputRequirement.REQUIRED;
			}
		}
		return req;
	}

	private static boolean isRuntimeFederatedInput(Hop input,
			java.util.Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> materialize,
			java.util.Map<Long, FederatedRefedRegistry.AnchorSpec> refed) {
		if (input == null)
			return false;
		if (isFederatedInitDataOp(input))
			return true;

		if (input instanceof DataOp) {
			DataOp dataOp = (DataOp) input;
			OpOpData op = dataOp.getOp();
			if (op == OpOpData.TRANSIENTREAD) {
				String name = dataOp.getName();
				if (name == null)
					return false;
				// Fed-init reads are always runtime federated.
				if (FederatedPlannerUtils.isFedInitVar(name))
					return true;
				// If we have a registered anchor key, the symbol table variable is federated
				// even if the current hop is planned as CP/LOUT.
				String anchorKey = FederatedPlannerUtils.getFedAnchorKey(name);
				if (anchorKey != null && !isVarAnchorKey(anchorKey))
					return true;
				// Transient reads are runtime-federated only if planned as FED/FOUT. Do not treat
				// CP/LOUT reads as federated even if a refed/materialize is registered for them,
				// because the federated value exists only after the upload lop.
				ExecType exec = getPlannedExecType(input);
				return exec == ExecType.FED && !input.hasLocalOutput();
			}
			if (op == OpOpData.TRANSIENTWRITE) {
				// TWrite forwards its input into the symbol table; if the input is federated,
				// the written variable is federated regardless of TWrite exec type.
				if (dataOp.hasFederatedOutput())
					return true;
				Hop in = dataOp.getInput() != null && !dataOp.getInput().isEmpty() ? dataOp.getInput().get(0) : null;
				if (in != null && isRuntimeFederatedInput(in, materialize, refed))
					return true;
				ExecType exec = getPlannedExecType(input);
				return exec == ExecType.FED && !input.hasLocalOutput();
			}
			return false;
		}

		if ((materialize != null && materialize.containsKey(input.getHopID()))
			|| (refed != null && refed.containsKey(input.getHopID())))
			return true;

		ExecType exec = getPlannedExecType(input);
		if (exec != ExecType.FED || input.hasLocalOutput())
			return false;
		// If the planner forced a federated output, treat it as federated regardless
		// of input detection to keep plan/exec consistent.
		if (input.hasFederatedOutput())
			return true;
		// For intermediate FED ops with local output, require at least one runtime federated input.
		return hasRuntimeFederatedInput(input, materialize, refed);
	}

	private static boolean hasRuntimeFederatedInput(Hop hop,
			java.util.Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> materialize,
			java.util.Map<Long, FederatedRefedRegistry.AnchorSpec> refed) {
		if (hop == null || hop.getInput() == null)
			return false;
		for (Hop input : hop.getInput()) {
			if (isRuntimeFederatedInput(input, materialize, refed))
				return true;
		}
		return false;
	}

	private static boolean isFederatedSourceOp(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (!(hop instanceof DataOp))
			return false;
		DataOp dataOp = (DataOp) hop;
		if (dataOp.getOp() != OpOpData.TRANSIENTREAD)
			return false;
		if (getPlannedExecType(hop) == ExecType.FED)
			return true;
		if (hop.hasFederatedOutput())
			return true;
		if (FederatedPlannerUtils.getFedAnchorKey(dataOp.getName()) != null)
			return true;
		return fTypeMap != null && fTypeMap.get(hop.getHopID()) != null;
	}

	private static Hop selectCpfoutCandidateInput(Hop parent, java.util.Map<Long, FType> fTypeMap,
			AnchorSelection blockAnchor) {
		if (parent == null || parent.getInput() == null)
			return null;
		Hop fallback = null;
		InputRequirement fallbackReq = null;
		for (int i = 0; i < parent.getInput().size(); i++) {
			Hop input = parent.getInput().get(i);
			if (input == null || !input.getDataType().isMatrix())
				continue;
			ExecType exec = getPlannedExecType(input);
			if (exec == ExecType.FED && input.hasFederatedOutput())
				continue;
			if (!canGenerateCpfoutCandidate(input, fTypeMap, blockAnchor))
				continue;
			InputRequirement req = classifyInput(parent, input, i, fTypeMap);
			if (req == InputRequirement.REQUIRED)
				return input;
			if (fallback == null || req.ordinal() < fallbackReq.ordinal()) {
				fallback = input;
				fallbackReq = req;
			}
		}
		return fallback;
	}

	public static boolean hasCpfoutCandidateInput(Hop parent, java.util.Map<Long, FType> fTypeMap) {
		return selectCpfoutCandidateInput(parent, fTypeMap, null) != null;
	}

	public static boolean canSatisfyFederatedInputs(Hop parent, java.util.Map<Long, FType> fTypeMap) {
		return canSatisfyFederatedInputs(parent, fTypeMap, null, false);
	}

	/**
	 * Planner-side federated input feasibility check based purely on inferred/planned FTypes.
	 *
	 * <p>This variant is intended for planners that do not set {@code ExecType} / {@code FederatedOutput}
	 * markers on hops during candidate enumeration (e.g., DP/MinST). In such cases, the {@code fTypeMap}
	 * is treated as the source of truth for whether an input is planned to be federated.</p>
	 */
	public static boolean canSatisfyFederatedInputsFromFTypes(Hop parent, java.util.Map<Long, FType> fTypeMap) {
		return canSatisfyFederatedInputs(parent, fTypeMap, null, true);
	}

	private static boolean canSatisfyFederatedInputs(Hop parent, java.util.Map<Long, FType> fTypeMap,
			AnchorSelection blockAnchor, boolean treatFTypeMapAsPlannedFederatedInputs) {
		if (parent == null || parent.getInput() == null)
			return true;
		AnchorKey globalAnchorKey = treatFTypeMapAsPlannedFederatedInputs ? selectGlobalAnchorKey(fTypeMap) : null;
		AnchorSelection requiredAnchor = null;
		AnchorSelection consumerAnchor = null;
		AnchorSelection optionalAnchor = null;
		boolean hasRequiredMatrix = false;
		boolean hasUnmaterializableLocal = false;
		List<Integer> requiredIndices = new ArrayList<>();

		for (int i = 0; i < parent.getInput().size(); i++) {
			Hop input = parent.getInput().get(i);
			if (input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				continue;
			// RightIndex/LeftIndex semantics: index vectors (e.g., 1:K) may be represented as matrices but are
			// broadcasted as metadata to workers. They do not need to be federated inputs for FED execution.
			if (parent instanceof IndexingOp && i > 0)
				continue;

			InputRequirement req = resolveTargetRequirement(parent, input, i, fTypeMap, blockAnchor);
			boolean plannedFed = treatFTypeMapAsPlannedFederatedInputs
				? (fTypeMap != null && fTypeMap.get(input.getHopID()) != null)
				: isPlannedFederatedInput(input, fTypeMap);

			AnchorSelection plannedAnchor = null;
			if (plannedFed) {
				AnchorKey key = buildAnchorKey(input, fTypeMap);
				plannedAnchor = new AnchorSelection(key, input);
			}
			else {
				boolean canMaterialize = canGenerateCpfoutCandidate(input, fTypeMap, blockAnchor);
				if (canMaterialize) {
					plannedAnchor = selectCpfoutAnchorForParent(parent, input, fTypeMap, blockAnchor,
						treatFTypeMapAsPlannedFederatedInputs);
					if (plannedAnchor != null)
						plannedFed = true;
					else
						hasUnmaterializableLocal = true;
				}
				else if (globalAnchorKey != null && canGenerateCpfoutCandidateFromFTypes(input, fTypeMap)) {
					plannedFed = true;
					plannedAnchor = new AnchorSelection(globalAnchorKey, null);
				}
				else {
					hasUnmaterializableLocal = true;
				}
			}

			if (req == InputRequirement.OPTIONAL) {
				if (plannedFed) {
					if (optionalAnchor == null
						|| (optionalAnchor.key == null && plannedAnchor != null && plannedAnchor.key != null)) {
						optionalAnchor = plannedAnchor;
					}
					continue;
				}
				// Optional-but-local inputs still require federation for FED exec.
			}

			hasRequiredMatrix = true;
			if (plannedFed) {
				if (plannedAnchor != null && plannedAnchor.key != null) {
					if (requiredAnchor == null || requiredAnchor.key == null)
						requiredAnchor = plannedAnchor;
					else if (!anchorsCompatible(requiredAnchor.key, plannedAnchor.key))
						return false;
				}
				else if (requiredAnchor == null) {
					requiredAnchor = plannedAnchor;
				}
			}
			else {
				requiredIndices.add(i);
			}
		}

		if (!hasRequiredMatrix)
			return true;
		if (hasUnmaterializableLocal)
			return false;

		if (!requiredIndices.isEmpty()) {
			// Prefer an anchor with a concrete key if we need to upload any required local inputs.
			if ((requiredAnchor == null || requiredAnchor.key == null) && optionalAnchor != null
				&& optionalAnchor.key != null) {
				requiredAnchor = optionalAnchor;
			}
			if (requiredAnchor == null || requiredAnchor.key == null)
				consumerAnchor = selectAnchor(parent, fTypeMap, true, false, blockAnchor);
		}

		for (int idx : requiredIndices) {
			Hop input = parent.getInput().get(idx);
			if (!canGenerateCpfoutCandidate(input, fTypeMap, blockAnchor))
				return false;

			AnchorSelection selection = null;
			if (requiredAnchor != null && requiredAnchor.key != null)
				selection = requiredAnchor;
			else if (consumerAnchor != null)
				selection = consumerAnchor;
			else
				selection = selectAnchor(input, fTypeMap, true, false, blockAnchor);

			// If we don't have a viable anchor key yet, try to find one via CP->FOUT anchoring.
			if (selection == null || selection.key == null) {
				selection = selectCpfoutAnchorForParent(parent, input, fTypeMap, blockAnchor,
					treatFTypeMapAsPlannedFederatedInputs);
			}
			if (selection == null || selection.key == null) {
				if (globalAnchorKey != null)
					selection = new AnchorSelection(globalAnchorKey, null);
				else
					return false;
			}

			if (requiredAnchor == null || requiredAnchor.key == null)
				requiredAnchor = selection;
			else if (!anchorsCompatible(requiredAnchor.key, selection.key))
				return false;
		}
		return requiredAnchor != null;
	}

	private static AnchorSelection selectCpfoutAnchorForParent(Hop parent, Hop input,
			java.util.Map<Long, FType> fTypeMap, AnchorSelection blockAnchor,
			boolean treatFTypeMapAsPlannedFederatedInputs) {
		if (parent == null || input == null)
			return null;
		if (!canGenerateCpfoutCandidate(input, fTypeMap, blockAnchor))
			return null;
		ParentAnchor parentAnchor = determineParentAnchor(parent, input, fTypeMap,
				treatFTypeMapAsPlannedFederatedInputs, false, false);
		if (parentAnchor == null || parentAnchor.isEmpty() || parentAnchor.key == null)
			return null;
		try {
			validateAnchorTypeSupported(input, parentAnchor.anchorHop, fTypeMap);
		}
		catch (DMLRuntimeException ex) {
			return null;
		}
		return new AnchorSelection(parentAnchor.key, parentAnchor.anchorHop);
	}

	private static String describeInputs(Hop hop) {
		if (hop == null || hop.getInput() == null)
			return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < hop.getInput().size(); i++) {
			Hop in = hop.getInput().get(i);
			if (i > 0)
				sb.append(", ");
			if (in == null) {
				sb.append("null");
				continue;
			}
			sb.append(in.getHopID()).append(":").append(in.getOpString());
			sb.append(":").append(in.getDataType());
			sb.append(":").append(getPlannedExecType(in));
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * If a matrix-valued hop is planned as CP (local) but is consumed by a FED hop as a REQUIRED
	 * matrix input, it must be uploaded (CP-&gt;FOUT) regardless of whether the FED hop already has
	 * another federated input. Otherwise, FED execution would implicitly rely on runtime fallbacks
	 * for local matrix inputs, which can break alignment assumptions and lead to incorrect results.
	 */
	private static boolean requiresCpfoutForFedParents(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (hop == null || hop.getParent() == null || hop.getParent().isEmpty())
			return false;
		if (hop.getDataType() == null || !hop.getDataType().isMatrix())
			return false;
		for (Hop parent : hop.getParent()) {
			if (parent == null)
				continue;
			ExecType exec = getPlannedExecType(parent);
			if (exec != ExecType.FED)
				continue;
			// FED init/read ops can run without any federated inputs.
			if (isFederatedInitDataOp(parent))
				continue;

			List<Hop> inputs = parent.getInput();
			if (inputs == null)
				continue;
			int targetIndex = -1;
			for (int i = 0; i < inputs.size(); i++) {
				if (inputs.get(i) == hop) {
					targetIndex = i;
					break;
				}
			}
			if (targetIndex < 0)
				continue;

			InputRequirement req = classifyTargetRequirement(parent, hop, targetIndex, fTypeMap);
			if (req != InputRequirement.OPTIONAL)
				return true;
		}
		return false;
	}

	private static void registerTransientWriteAnchor(DataOp tWrite, java.util.Map<Long, FType> fTypeMap,
			AnchorSelection blockAnchor, long sbId, boolean conditionalContext) {
		if (tWrite == null)
			return;
		String varName = tWrite.getName();
		if (varName == null || varName.isEmpty())
			return;
		if (ENABLE_TRANSREAD_DEBUG && "Y".equals(varName)) {
			System.out.println("[TransReadRefedDebug] tWrite hop=" + tWrite.getHopID()
				+ " conditional=" + conditionalContext
				+ " fout=" + tWrite.getFederatedOutput());
		}
		// If the write is not federated (FOUT), the variable becomes local; clear any stale anchors
		// and fed-init markers so TRs don't get treated as federated sources.
		if (tWrite.getFederatedOutput() != FederatedOutput.FOUT) {
			FederatedPlannerUtils.removeFedAnchorKey(varName);
			if (!conditionalContext)
				FederatedPlannerUtils.removeFedInitVar(varName);
		}
		List<Hop> inputs = tWrite.getInput();
		if (inputs == null || inputs.isEmpty())
			return;
		Hop input = inputs.get(0);
		if (input == null)
			return;
		if (!isRuntimeFederatedInput(input, null, null)) {
			// Local assignment overwrites any previous federated state. Preserve only a VAR anchor
			// (if we had one) so refed can still reuse worker metadata without treating the value as federated.
			if (ENABLE_TRANSREAD_DEBUG && "Y".equals(varName)) {
				System.out.println("[TransReadRefedDebug] tWrite local override hop=" + tWrite.getHopID());
			}
			String existingAnchor = FederatedPlannerUtils.getFedAnchorKey(varName);
			boolean hadAnchor = existingAnchor != null || FederatedPlannerUtils.isFedInitVar(varName);
			FederatedPlannerUtils.removeFedAnchorKey(varName);
			FederatedPlannerUtils.removeFedInitVar(varName);
			if (hadAnchor) {
				FType fType = getKnownFType(input, fTypeMap);
				if (fType == null) {
					FType axis = FederatedPlannerUtils.getVectorAxis(input);
					if (axis != null)
						fType = axis;
				}
				String varKey = "VAR:" + varName;
				if (fType != null)
					varKey = varKey + "|" + fType.name();
				FederatedPlannerUtils.registerFedAnchorKey(varName, varKey);
				if (ENABLE_TRANSREAD_DEBUG && "Y".equals(varName)) {
					System.out.println("[TransReadRefedDebug] tWrite set VAR anchor=" + varKey);
				}
			}
			return;
		}

		String anchorKey = null;
		AnchorKey key = buildAnchorKey(input, fTypeMap);
		if (key != null && key.value instanceof String)
			anchorKey = (String) key.value;

		if (anchorKey == null) {
			String signature = findFedInitSignature(input);
			if (signature != null) {
				FType fType = getKnownFType(input, fTypeMap);
				AnchorKey sigKey = buildAnchorKeyFromSignature(signature, fType);
				if (sigKey != null && sigKey.value instanceof String)
					anchorKey = (String) sigKey.value;
			}
		}

		if (anchorKey == null && blockAnchor != null
			&& blockAnchor.key != null && blockAnchor.key.value instanceof String) {
			anchorKey = (String) blockAnchor.key.value;
		}
		else if (anchorKey != null && anchorKey.startsWith("VAR:") && blockAnchor != null
			&& blockAnchor.key != null && blockAnchor.key.value instanceof String) {
			anchorKey = (String) blockAnchor.key.value;
		}

		if (anchorKey != null) {
			String existing = FederatedPlannerUtils.getFedAnchorKey(varName);
			if (existing == null || existing.equals(anchorKey))
				FederatedPlannerUtils.registerFedAnchorKey(varName, anchorKey);
		}

		if (input instanceof DataOp) {
			DataOp dataOp = (DataOp) input;
			String signature = null;
			if (dataOp.getOp() == OpOpData.FEDERATED)
				signature = FederatedPlannerUtils.deriveFedInitSignature(dataOp);
			else if (dataOp.getOp() == OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.isFedInitVar(dataOp.getName()))
				signature = FederatedPlannerUtils.getFedInitSignature(dataOp.getName());
			if (signature != null) {
				FType fType = getKnownFType(input, fTypeMap);
				FederatedPlannerUtils.registerFedInitVar(varName, fType, signature);
			}
		}
	}

	private static void registerFromStatementBlock(StatementBlock sb, java.util.Map<Long, FType> fTypeMap,
			AnchorSelection fallbackAnchor, boolean conditionalContext) {
		if (sb == null)
			return;
		Set<Hop> roots = new HashSet<>();
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			if (isb.getPredicateHops() != null)
				roots.add(isb.getPredicateHops());
			if (!roots.isEmpty())
				registerFromHopsInternal(new ArrayList<>(roots), false, fTypeMap, sb.getSBID(),
						null, null, fallbackAnchor, conditionalContext);
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			for (StatementBlock inner : istmt.getIfBody())
				registerFromStatementBlock(inner, fTypeMap, fallbackAnchor, true);
			for (StatementBlock inner : istmt.getElseBody())
				registerFromStatementBlock(inner, fTypeMap, fallbackAnchor, true);
		} else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			if (fsb.getFromHops() != null)
				roots.add(fsb.getFromHops());
			if (fsb.getToHops() != null)
				roots.add(fsb.getToHops());
			if (fsb.getIncrementHops() != null)
				roots.add(fsb.getIncrementHops());
			if (!roots.isEmpty())
				registerFromHopsInternal(new ArrayList<>(roots), false, fTypeMap, sb.getSBID(),
						null, null, fallbackAnchor, conditionalContext);
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				registerFromStatementBlock(inner, fTypeMap, fallbackAnchor, true);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			if (wsb.getPredicateHops() != null)
				roots.add(wsb.getPredicateHops());
			if (!roots.isEmpty())
				registerFromHopsInternal(new ArrayList<>(roots), false, fTypeMap, sb.getSBID(),
						null, null, fallbackAnchor, conditionalContext);
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			for (StatementBlock inner : wstmt.getBody())
				registerFromStatementBlock(inner, fTypeMap, fallbackAnchor, true);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				registerFromStatementBlock(inner, fTypeMap, fallbackAnchor, true);
		} else {
			if (sb.getHops() != null)
				roots.addAll(sb.getHops());
			if (!roots.isEmpty())
				registerFromHopsInternal(new ArrayList<>(roots), false, fTypeMap, sb.getSBID(),
						null, null, fallbackAnchor, conditionalContext);
		}
	}

	private static AnchorSelection buildGlobalAnchorForProgram(DMLProgram prog, java.util.Map<Long, FType> fTypeMap) {
		if (prog == null)
			return null;
		Set<Hop> roots = new HashSet<>();
		for (StatementBlock sb : prog.getStatementBlocks())
			collectRoots(sb, roots);
		if (roots.isEmpty())
			return null;
		AnchorSelection anchor = buildSignatureAnchorFromRoots(new ArrayList<>(roots), fTypeMap);
		if (anchor == null)
			anchor = buildSingleDataOpAnchorFromRoots(new ArrayList<>(roots), fTypeMap);
		if (anchor == null) {
			List<Hop> all = collectAllHops(new ArrayList<>(roots));
			anchor = buildFederatedAnchorFromHops(all, fTypeMap);
		}
		return anchor;
	}

	private static AnchorSelection buildGlobalAnchorForFunction(FunctionStatementBlock function,
			java.util.Map<Long, FType> fTypeMap) {
		if (function == null)
			return null;
		FunctionStatement fstmt = (FunctionStatement) function.getStatement(0);
		if (fstmt == null)
			return null;
		Set<Hop> roots = new HashSet<>();
		for (StatementBlock inner : fstmt.getBody())
			collectRoots(inner, roots);
		if (roots.isEmpty())
			return null;
		AnchorSelection anchor = buildSignatureAnchorFromRoots(new ArrayList<>(roots), fTypeMap);
		if (anchor == null)
			anchor = buildSingleDataOpAnchorFromRoots(new ArrayList<>(roots), fTypeMap);
		if (anchor == null) {
			List<Hop> all = collectAllHops(new ArrayList<>(roots));
			anchor = buildFederatedAnchorFromHops(all, fTypeMap);
		}
		return anchor;
	}

	private static AnchorSelection buildAnchorSelectionFromRoots(List<Hop> roots,
			java.util.Map<Long, FType> fTypeMap,
			java.util.Map<String, String> runtimeSignatures,
			java.util.Map<String, FType> runtimeTypes) {
		if (roots == null || roots.isEmpty())
			return null;
		List<Hop> all = collectAllHops(roots);
		AnchorSelection blockAnchor = buildBlockAnchorSelection(all, fTypeMap, runtimeSignatures);
		if (blockAnchor == null) {
			AnchorSelection synthetic = buildSyntheticAnchorSelection(all, fTypeMap, runtimeSignatures, runtimeTypes);
			if (synthetic != null)
				blockAnchor = synthetic;
		}
		return blockAnchor;
	}

	private static AnchorSelection buildSignatureAnchorFromRoots(List<Hop> roots, java.util.Map<Long, FType> fTypeMap) {
		if (roots == null || roots.isEmpty())
			return null;
		List<Hop> all = collectAllHops(roots);
		java.util.Map<String, FType> signatureTypes = new java.util.HashMap<>();
		java.util.Set<String> ambiguous = new java.util.HashSet<>();
		for (Hop hop : all) {
			// For stable anchors, consider only actual federated data objects (fed-init reads),
			// not intermediate ops or transient writes.
			if (!(hop instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hop;
			OpOpData op = dataOp.getOp();
			if (op != OpOpData.TRANSIENTREAD && op != OpOpData.FEDERATED)
				continue;
			boolean isFedInitVar = op == OpOpData.TRANSIENTREAD
				&& FederatedPlannerUtils.isFedInitVar(dataOp.getName());
			boolean hasVarAnchor = op == OpOpData.TRANSIENTREAD
				&& FederatedPlannerUtils.getFedAnchorKey(dataOp.getName()) != null;
			if (op == OpOpData.TRANSIENTREAD && !isFedInitVar && !hasVarAnchor)
				continue;
			if (!isBlockAnchorCandidate(hop))
				continue;
			if (!isFederatedInput(hop, fTypeMap))
				continue;
			String signature = findFedInitSignature(hop);
			if (signature == null)
				continue;
			FType fType = getKnownFType(hop, fTypeMap);
			FType existing = signatureTypes.get(signature);
			if (existing == null)
				signatureTypes.put(signature, fType);
			else if (existing != null && fType != null && existing != fType)
				ambiguous.add(signature);
		}

		AnchorKey selectedKey = null;
		Hop selectedAnchor = null;
		for (Hop hop : all) {
			if (!(hop instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hop;
			OpOpData op = dataOp.getOp();
			if (op != OpOpData.TRANSIENTREAD && op != OpOpData.FEDERATED)
				continue;
			boolean isFedInitVar = op == OpOpData.TRANSIENTREAD
				&& FederatedPlannerUtils.isFedInitVar(dataOp.getName());
			boolean hasVarAnchor = op == OpOpData.TRANSIENTREAD
				&& FederatedPlannerUtils.getFedAnchorKey(dataOp.getName()) != null;
			if (op == OpOpData.TRANSIENTREAD && !isFedInitVar && !hasVarAnchor)
				continue;
			if (!isBlockAnchorCandidate(hop))
				continue;
			if (!isFederatedInput(hop, fTypeMap))
				continue;
			String signature = findFedInitSignature(hop);
			if (signature == null)
				continue;
			if (ambiguous.contains(signature))
				return null;
			FType fType = getKnownFType(hop, fTypeMap);
			if (fType == null)
				fType = signatureTypes.get(signature);
			AnchorKey key = buildAnchorKeyFromSignature(signature, fType);
			if (key == null)
				continue;
			if (selectedKey == null) {
				selectedKey = key;
				selectedAnchor = hop;
			} else if (!anchorsCompatible(selectedKey, key)) {
				return null;
			}
		}

		if (selectedKey == null || selectedAnchor == null)
			return null;
		return new AnchorSelection(selectedKey, selectedAnchor);
	}

	private static AnchorSelection buildSingleDataOpAnchorFromRoots(List<Hop> roots,
			java.util.Map<Long, FType> fTypeMap) {
		if (roots == null || roots.isEmpty())
			return null;
		List<Hop> all = collectAllHops(roots);
		AnchorKey selectedKey = null;
		Hop selectedAnchor = null;
		for (Hop hop : all) {
			if (!(hop instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() == OpOpData.TRANSIENTWRITE) {
				List<Hop> inputs = dataOp.getInput();
				Hop input = (inputs != null && !inputs.isEmpty()) ? inputs.get(0) : null;
				if (input != null && isFederatedInput(input, fTypeMap)) {
					FType fType = getKnownFType(input, fTypeMap);
					String name = dataOp.getName();
					if (name != null && !name.isEmpty()) {
						String varKey = "VAR:" + name;
						if (fType != null)
							varKey = varKey + "|" + fType.name();
						AnchorKey key = new AnchorKey(AnchorKeyType.FEDINIT_SIGNATURE, varKey);
						if (selectedKey == null) {
							selectedKey = key;
							selectedAnchor = input;
						} else if (!anchorsCompatible(selectedKey, key)) {
							return null;
						}
					}
				}
				continue;
			}
			if (!isBlockAnchorCandidate(hop))
				continue;
			if (!isFederatedInput(hop, fTypeMap))
				continue;
			FType fType = getKnownFType(hop, fTypeMap);
			OpOpData op = dataOp.getOp();
			// Do not use transient writes or non-fedinit transient reads as stable anchors.
			if (op != OpOpData.TRANSIENTREAD && op != OpOpData.FEDERATED)
				continue;
			boolean isFedInitVar = op == OpOpData.TRANSIENTREAD
				&& FederatedPlannerUtils.isFedInitVar(dataOp.getName());
			boolean hasVarAnchor = op == OpOpData.TRANSIENTREAD
				&& FederatedPlannerUtils.getFedAnchorKey(dataOp.getName()) != null;
			if (op == OpOpData.TRANSIENTREAD && !isFedInitVar && !hasVarAnchor)
				continue;
			AnchorKey key = buildAnchorKeyForDataOp(dataOp, fType, fTypeMap);
			if (key == null)
				continue;
			if (selectedKey == null) {
				selectedKey = key;
				selectedAnchor = hop;
			} else if (!anchorsCompatible(selectedKey, key)) {
				return null;
			}
		}
		if (selectedKey == null || selectedAnchor == null)
			return null;
		return new AnchorSelection(selectedKey, selectedAnchor);
	}

	private static AnchorSelection findInputAnchorSelection(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (hop == null)
			return null;
		Set<Hop> visited = new HashSet<>();
		Deque<Hop> queue = new ArrayDeque<>();
		List<Hop> inputs = hop.getInput();
		if (inputs != null)
			queue.addAll(inputs);
		AnchorKey selectedKey = null;
		Hop selectedAnchor = null;
		while (!queue.isEmpty()) {
			Hop cur = queue.poll();
			if (cur == null || !visited.add(cur))
				continue;
			if (cur instanceof DataOp) {
				DataOp dataOp = (DataOp) cur;
				if (!isRuntimeFederatedInput(cur, null, null))
					continue;
				FType fType = getKnownFType(cur, fTypeMap);
				if (fType != null) {
					AnchorKey key = buildAnchorKeyForDataOp(dataOp, fType, fTypeMap);
					if (key != null) {
						if (selectedKey == null) {
							selectedKey = key;
							selectedAnchor = cur;
						} else if (!anchorsCompatible(selectedKey, key)) {
							return null;
						}
					}
				}
			}
			List<Hop> ins = cur.getInput();
			if (ins != null)
				queue.addAll(ins);
		}
		if (selectedKey == null || selectedAnchor == null)
			return null;
		return new AnchorSelection(selectedKey, selectedAnchor);
	}

	private static AnchorKey buildAnchorKeyForDataOp(DataOp dataOp, FType fType,
			java.util.Map<Long, FType> fTypeMap) {
		if (dataOp == null)
			return null;
		String signature = findFedInitSignature(dataOp);
		if (signature != null)
			return buildAnchorKeyFromSignature(signature, fType);
		if (dataOp.getOp() == OpOpData.TRANSIENTREAD || dataOp.getOp() == OpOpData.FEDERATED) {
			String name = dataOp.getName();
			if (name == null || name.isEmpty())
				return null;
			String varKey = "VAR:" + name;
			if (fType != null)
				varKey = varKey + "|" + fType.name();
			return new AnchorKey(AnchorKeyType.FEDINIT_SIGNATURE, varKey);
		}
		return buildAnchorKey(dataOp, fTypeMap);
	}

	private static String stripTrailingFTypeSuffix(String anchorKey) {
		if (anchorKey == null)
			return null;
		int sep = anchorKey.lastIndexOf('|');
		if (sep < 0 || sep == anchorKey.length() - 1)
			return anchorKey;
		String suffix = anchorKey.substring(sep + 1);
		try {
			FType.valueOf(suffix);
		}
		catch (IllegalArgumentException ex) {
			return anchorKey;
		}
		return anchorKey.substring(0, sep);
	}

	private static FType getFTypeFromAnchorKey(String anchorKey) {
		if (anchorKey == null)
			return null;
		int sep = anchorKey.lastIndexOf('|');
		if (sep < 0 || sep == anchorKey.length() - 1)
			return null;
		String suffix = anchorKey.substring(sep + 1);
		try {
			return FType.valueOf(suffix);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static List<Hop> collectAllHops(List<Hop> roots) {
		if (roots == null || roots.isEmpty())
			return Collections.emptyList();
		// Collect hops in a post-order (inputs-first) traversal to ensure that
		// CP->FOUT decisions for potential anchors (inputs) are made before their
		// consumers attempt to select anchors based on fTypeMap.
		Set<Hop> visited = new HashSet<>();
		List<Hop> all = new ArrayList<>();
		Deque<Hop> stack = new ArrayDeque<>();
		Deque<Boolean> expanded = new ArrayDeque<>();

		for (Hop root : roots) {
			if (root == null)
				continue;
			stack.push(root);
			expanded.push(false);
			while (!stack.isEmpty()) {
				Hop cur = stack.pop();
				boolean isExpanded = expanded.pop();
				if (cur == null)
					continue;
				if (isExpanded) {
					all.add(cur);
					continue;
				}
				if (!visited.add(cur))
					continue;
				stack.push(cur);
				expanded.push(true);
				List<Hop> inputs = cur.getInput();
				if (inputs == null || inputs.isEmpty())
					continue;
				for (Hop in : inputs) {
					if (in == null)
						continue;
					stack.push(in);
					expanded.push(false);
				}
			}
		}
		return all;
	}

	private static AnchorSelection resolveFallbackAnchor(List<Hop> hops, java.util.Map<Long, FType> fTypeMap,
			AnchorSelection fallback) {
		if (fallback == null || fallback.key == null || hops == null || hops.isEmpty())
			return null;

		// Fast path: same hop id exists in the current hop graph.
		if (fallback.anchorHop != null) {
			long hopId = fallback.anchorHop.getHopID();
			for (Hop hop : hops) {
				if (hop != null && hop.getHopID() == hopId)
					return new AnchorSelection(fallback.key, hop);
			}
		}

		// Match by semantic anchor key (e.g., fed-init signature) to handle distinct hop instances.
		for (Hop hop : hops) {
			if (hop == null)
				continue;
			AnchorKey key = buildAnchorKey(hop, fTypeMap);
			if (key != null && anchorsCompatible(key, fallback.key))
				return new AnchorSelection(key, hop);
		}
		String anchorVar = null;
		if (fallback.anchorHop instanceof DataOp)
			anchorVar = ((DataOp) fallback.anchorHop).getName();
		if (anchorVar == null || anchorVar.isEmpty()) {
			Object value = fallback.key.value;
			if (value instanceof String) {
				String keyStr = stripTrailingFTypeSuffix((String) value);
				if (keyStr != null && keyStr.startsWith("VAR:"))
					anchorVar = keyStr.substring("VAR:".length());
			}
		}
		if (anchorVar == null || anchorVar.isEmpty())
			return null;
		int blen = ConfigurationManager.getBlocksize();
		Hop anchorHop = new DataOp(anchorVar, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, anchorVar, -1, -1, -1, blen);
		if (fallback.key.value instanceof String) {
			FType fType = getFTypeFromAnchorKey((String) fallback.key.value);
			if (fType != null && fTypeMap != null)
				fTypeMap.put(anchorHop.getHopID(), fType);
		}
		return new AnchorSelection(fallback.key, anchorHop);
	}

	private static AnchorSelection buildFederatedAnchorFromHops(List<Hop> hops, java.util.Map<Long, FType> fTypeMap) {
		if (hops == null || hops.isEmpty())
			return null;
		AnchorKey selectedKey = null;
		Hop selectedAnchor = null;
		boolean incompatible = false;
		for (Hop hop : hops) {
			if (hop == null)
				continue;
			if (!isFederatedInput(hop, fTypeMap))
				continue;
			AnchorKey key = buildAnchorKey(hop, fTypeMap);
			if (key == null)
				continue;
			if (selectedKey == null) {
				selectedKey = key;
				selectedAnchor = hop;
			} else if (!anchorsCompatible(selectedKey, key)) {
				incompatible = true;
			}
		}
		if (selectedKey == null || selectedAnchor == null)
			return null;
		if (incompatible)
			return new AnchorSelection(null, selectedAnchor);
		return new AnchorSelection(selectedKey, selectedAnchor);
	}

	private static void collectRoots(StatementBlock sb, Set<Hop> roots) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			if (isb.getPredicateHops() != null)
				roots.add(isb.getPredicateHops());
			for (StatementBlock inner : istmt.getIfBody())
				collectRoots(inner, roots);
			for (StatementBlock inner : istmt.getElseBody())
				collectRoots(inner, roots);
		} else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			if (fsb.getFromHops() != null)
				roots.add(fsb.getFromHops());
			if (fsb.getToHops() != null)
				roots.add(fsb.getToHops());
			if (fsb.getIncrementHops() != null)
				roots.add(fsb.getIncrementHops());
			for (StatementBlock inner : fstmt.getBody())
				collectRoots(inner, roots);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			if (wsb.getPredicateHops() != null)
				roots.add(wsb.getPredicateHops());
			for (StatementBlock inner : wstmt.getBody())
				collectRoots(inner, roots);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				collectRoots(inner, roots);
		} else {
			if (sb.getHops() != null)
				roots.addAll(sb.getHops());
		}
	}

	public static boolean canGenerateCpfoutCandidate(Hop hop) {
		return canGenerateCpfoutCandidate(hop, null);
	}

	public static boolean canGenerateCpfoutCandidate(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (hop == null || hop.getParent() == null || hop.getParent().isEmpty())
			return false;
		if (isRecompileRegion(hop))
			return false;
		if (hop instanceof DataOp) {
			OpOpData op = ((DataOp) hop).getOp();
			if (op == OpOpData.TRANSIENTREAD || op == OpOpData.TRANSIENTWRITE)
				return false;
		}
		AnchorSelection selection = selectAnchor(hop, fTypeMap, false, false, null);
		if (selection == null || selection.key == null)
			return false;
		// PART/OTHER anchors are unsupported for CP->FOUT refed and should hard-fail to avoid
		// silently falling back to a plan with undefined semantics.
		validateAnchorTypeSupported(hop, selection.anchorHop, fTypeMap);
		return true;
	}

	public static boolean canGenerateCpfoutCandidateFromFTypes(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (canGenerateCpfoutCandidate(hop, fTypeMap))
			return true;
		if (hop == null || hop.getParent() == null || hop.getParent().isEmpty())
			return false;
		if (isRecompileRegion(hop))
			return false;
		if (hop instanceof DataOp) {
			OpOpData op = ((DataOp) hop).getOp();
			if (op == OpOpData.TRANSIENTREAD || op == OpOpData.TRANSIENTWRITE)
				return false;
		}
		return selectGlobalAnchorKey(fTypeMap) != null;
	}

	/**
	 * Adjust CP->FOUT planning FType using placement metadata (anchorKey) when the anchor
	 * is not available in the current block.
	 *
	 * <p>If the global anchorKey implies ROW/COL partitioning but the local hop's
	 * vector axis or axis length does not match, runtime materialization will
	 * broadcast. In that case, return BROADCAST so the cost model accounts for the
	 * replicated upload.</p>
	 */
	public static FType adjustCpFoutFTypeForAnchorKey(Hop hop, FType fType) {
		if (hop == null || fType == null || fType == FType.BROADCAST)
			return fType;
		AnchorKey globalKey = selectGlobalAnchorKey(null);
		if (globalKey == null || globalKey.value == null)
			return fType;
		if (!(globalKey.value instanceof String))
			return fType;
		String anchorKey = (String) globalKey.value;
		FType anchorType = getFTypeFromAnchorKey(anchorKey);
		if (anchorType != FType.ROW && anchorType != FType.COL)
			return fType;
		FType vectorAxis = FederatedPlannerUtils.getVectorAxis(hop);
		if (vectorAxis != null && vectorAxis != anchorType)
			return FType.BROADCAST;
		if (hop.dimsKnown()) {
			Long axisLen = parseAxisLenFromSignature(anchorKey);
			if (axisLen != null) {
				long hopAxisLen = (anchorType == FType.ROW) ? hop.getDim1() : hop.getDim2();
				if (hopAxisLen > 0 && hopAxisLen != axisLen)
					return FType.BROADCAST;
			}
		}
		return fType;
	}

	private static boolean canGenerateCpfoutCandidate(Hop hop, java.util.Map<Long, FType> fTypeMap,
			AnchorSelection blockAnchor) {
		if (hop == null || hop.getParent() == null || hop.getParent().isEmpty())
			return false;
		if (isRecompileRegion(hop))
			return false;
		if (hop instanceof DataOp) {
			OpOpData op = ((DataOp) hop).getOp();
			if (op == OpOpData.TRANSIENTREAD || op == OpOpData.TRANSIENTWRITE)
				return false;
		}
		AnchorSelection selection = selectAnchorWithinBlock(hop, fTypeMap, false, false, blockAnchor);
		if (selection == null || selection.key == null)
			return false;
		// PART/OTHER anchors are unsupported for CP->FOUT refed and should hard-fail to avoid
		// silently falling back to a plan with undefined semantics.
		validateAnchorTypeSupported(hop, selection.anchorHop, fTypeMap);
		return true;
	}

	private static boolean isRecompileRegion(Hop hop) {
		if (hop == null)
			return false;
		if (hop.requiresRecompile())
			return true;
		List<Hop> inputs = hop.getInput();
		if (inputs == null)
			return false;
		for (Hop in : inputs) {
			if (in != null && in.requiresRecompile())
				return true;
		}
		return false;
	}

	private static void validateAndRegister(Hop hop, java.util.Map<Long, FType> fTypeMap, long sbId,
			AnchorSelection blockAnchor) {
		if (!hop.getDataType().isMatrix())
			throw new DMLRuntimeException("CP->FOUT refed supports only matrix outputs for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		if (hop.getDataType().isFrame())
			throw new DMLRuntimeException("CP->FOUT refed does not support frame outputs for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		if (isFederatedInitDataOp(hop)) {
			if (LOG.isDebugEnabled())
				LOG.debug("CP->FOUT decision: SKIP (already_federated) hopID=" + hop.getHopID()
					+ " op=" + hop.getOpString());
			return;
		}
		if (hop.hasFederatedOutput()) {
			ExecType exec = getPlannedExecType(hop);
			if (exec == ExecType.FED && !hop.hasLocalOutput() && isRuntimeFederatedInput(hop, null, null)) {
				if (LOG.isDebugEnabled())
					LOG.debug("CP->FOUT decision: SKIP (already_federated_output) hopID=" + hop.getHopID()
						+ " op=" + hop.getOpString());
				return;
			}
		}

		AnchorSelection selection = selectAnchorWithinBlock(hop, fTypeMap, true, true, blockAnchor);
		if (selection == null) {
			if (hop.hasFederatedOutput())
				throw new DMLRuntimeException("CP->FOUT refed requires an anchor for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
			if (LOG.isDebugEnabled())
				LOG.debug("CP->FOUT decision: LOUT (no_single_anchor) hopID=" + hop.getHopID()
					+ " op=" + hop.getOpString());
			hop.setFederatedOutput(FederatedOutput.LOUT);
			return;
		}
		// Anchor mismatch: multiple incompatible FED parents exist. Do not pick an arbitrary anchor; fall back to local output.
		if (selection.key == null) {
			if (LOG.isDebugEnabled())
				LOG.debug("CP->FOUT decision: LOUT (anchor_mismatch) hopID=" + hop.getHopID()
					+ " op=" + hop.getOpString());
			hop.setFederatedOutput(FederatedOutput.LOUT);
			return;
		}
		registerCpfoutWithSelection(hop, fTypeMap, sbId, selection);
	}

	private static void validateAndRegisterRequired(Hop hop, java.util.Map<Long, FType> fTypeMap, long sbId,
			AnchorSelection selection) {
		if (hop == null)
			return;
		if (!hop.getDataType().isMatrix())
			throw new DMLRuntimeException("CP->FOUT refed supports only matrix outputs for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		if (hop.getDataType().isFrame())
			throw new DMLRuntimeException("CP->FOUT refed does not support frame outputs for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		if (isFederatedInitDataOp(hop))
			return;
		if (hop.hasFederatedOutput()) {
			ExecType exec = getPlannedExecType(hop);
			if (exec == ExecType.FED && !hop.hasLocalOutput() && isRuntimeFederatedInput(hop, null, null))
				return;
		}
		if (selection == null || selection.key == null)
			throw new DMLRuntimeException("CP->FOUT refed requires a federated anchor for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		if (selection.anchorHop != null && !isRuntimeFederatedInput(selection.anchorHop, null, null))
			throw new DMLRuntimeException("CP->FOUT refed requires a federated anchor for hop "
				+ hop.getHopID() + " (" + hop.getOpString() + ")");
		if (selection.anchorHop == null) {
			String anchorKey = toAnchorKeyString(selection);
			if (anchorKey == null)
				throw new DMLRuntimeException("CP->FOUT refed requires a federated anchor for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		}
		registerCpfoutWithSelection(hop, fTypeMap, sbId, selection);
	}

	private static long resolveHopSbId(long hopId, long fallback) {
		Long sbId = HOP_SBID_CACHE.get().get(hopId);
		return sbId != null ? sbId : fallback;
	}

	private static AnchorSelection selectAnchorWithinBlock(Hop hop, java.util.Map<Long, FType> fTypeMap,
			boolean onlyFedParents, boolean throwOnFailure, AnchorSelection blockAnchor) {
		if (blockAnchor != null && blockAnchor.key != null && blockAnchor.anchorHop != null
			&& isRuntimeFederatedInput(blockAnchor.anchorHop, null, null))
			return blockAnchor;
		return selectAnchor(hop, fTypeMap, onlyFedParents, throwOnFailure, blockAnchor);
	}

	private static void registerCpfoutWithSelection(Hop hop, java.util.Map<Long, FType> fTypeMap, long sbId,
			AnchorSelection selection) {
		if (hop == null)
			return;

		if (ENABLE_TRANSREAD_DEBUG && hop instanceof DataOp
				&& ((DataOp) hop).getOp() == OpOpData.TRANSIENTWRITE
				&& "Y".equals(((DataOp) hop).getName())) {
			Hop selAnchor = selection != null ? selection.anchorHop : null;
			System.out.println("[TransReadRefedDebug] registerCpfoutWithSelection TWrite Y hop=" + hop.getHopID()
				+ " anchor=" + (selection != null ? selection.key : null)
				+ " anchorHop=" + (selAnchor != null ? selAnchor.getHopID() + ":" + selAnchor.getOpString() : "null")
				+ " anchorFed=" + (selAnchor != null && isRuntimeFederatedInput(selAnchor, null, null)));
		}

		boolean isTransientRead = hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTREAD;
		if (isTransientRead) {
			ExecType exec = getPlannedExecType(hop);
			if (exec == ExecType.FED && !hop.hasLocalOutput())
				return;
		}

		long scopeId = (sbId >= 0) ? sbId : DEFAULT_SBID;

		// IMPORTANT: Distinguish between true CP->FOUT (local CP result uploaded) and
		// FED->LOUT->FOUT (federated exec that produces local output, then uploaded).
		//
		// For CP->FOUT we can safely mark the hop as FOUT, because its lop construction
		// does not depend on the federated-output marker beyond driving the refed/materialize.
		//
		// For FED->LOUT->FOUT we must NOT flip the hop's federated output to FOUT here:
		// some HOPs (e.g., AggBinaryOp with transpose-rewrite) use the federated-output marker
		// to choose internal FED lops, which would make the runtime plan inconsistent because
		// the underlying FED instructions can be "LOUT-only" by design (e.g., aggregate mmult).
		//
		// Instead, keep the hop placement as-is (LOUT) and rely on the registered
		// refed/materialize lop to create a federated version for downstream FED consumers.
		ExecType plannedExec = getPlannedExecType(hop);
		boolean isCpToFout = (plannedExec == null || plannedExec == ExecType.CP);
		if (isCpToFout) {
			if (!isTransientRead && hop.getFederatedOutput() != FederatedOutput.FOUT)
				hop.setFederatedOutput(FederatedOutput.FOUT);
			if (selection != null && selection.key != null)
				CPFOUT_ANCHOR_CACHE.put(hop.getHopID(), selection.key);
		}

		String anchorKey = toAnchorKeyString(selection);
		Hop anchorHop = (selection != null) ? selection.anchorHop : null;
		long anchorHopId = (anchorHop != null) ? anchorHop.getHopID() : -1;

		// AnchorKey-only fallback: allow CP->FOUT even when the concrete anchor hop is not visible in this block.
		if (anchorHop == null && anchorKey == null)
			throw new DMLRuntimeException("CP->FOUT refed requires an anchor for hop " + hop.getHopID()
				+ " (" + hop.getOpString() + ")");

		if (anchorHop != null)
			validateAnchorTypeSupported(hop, anchorHop, fTypeMap);

		FType anchorType = null;
		if (anchorHop != null) {
			anchorType = getKnownFType(anchorHop, fTypeMap);
			if (anchorType == null) {
				FType axis = FederatedPlannerUtils.getVectorAxis(anchorHop);
				if (axis != null)
					anchorType = axis;
			}
		}
		else if (anchorKey != null) {
			anchorType = getFTypeFromAnchorKey(anchorKey);
		}

		// TransientWrite must never be handled via fed_refed insertion: the written variable does not exist at the
		// time the refed would execute. Always materialize the TWrite input via fed_fout and wire it into the TWrite
		// during lop insertion.
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTWRITE) {
			Hop tWriteInput = null;
			List<Hop> inputs = hop.getInput();
			if (inputs != null && !inputs.isEmpty())
				tWriteInput = inputs.get(0);

			FType effective = anchorType != null ? anchorType : FType.FULL;
			String fTypeHint = toFTypeHint(effective);
			if (anchorHop != null && tWriteInput != null && tWriteInput.getDataType() != null
				&& tWriteInput.getDataType().isMatrix() && hasDimMismatch(tWriteInput, anchorHop, fTypeMap)) {
				boolean broadcastMismatch = isVectorAxisMismatch(tWriteInput, anchorHop, fTypeMap)
					|| isMaterializeAxisMismatch(tWriteInput, anchorHop, fTypeMap);
				if (broadcastMismatch) {
					effective = FType.BROADCAST;
					fTypeHint = "BROADCAST";
				}
			}
			else if (anchorHop == null) {
				Hop probe = (tWriteInput != null) ? tWriteInput : hop;
				effective = adjustCpFoutFTypeForAnchorKey(probe, effective);
				fTypeHint = (effective == FType.BROADCAST) ? "BROADCAST" : toFTypeHint(effective);
			}

			if (fTypeMap != null)
				fTypeMap.put(hop.getHopID(), effective);
			FederatedRefedRegistry.remove(scopeId, hop.getHopID());
			String anchorLabel = (anchorHop != null) ? findAnchorLabel(anchorHop) : null;
			if (ENABLE_TRANSREAD_DEBUG && "Y".equals(((DataOp) hop).getName()))
				System.out.println("[TransReadRefedDebug] TWrite Y anchorLabel=" + anchorLabel);
			FederatedFoutMaterializeRegistry.register(scopeId, hop.getHopID(), anchorHopId, fTypeHint, anchorLabel,
				anchorKey);
			return;
		}

		// Scalar-like matrices must always be broadcasted.
		if (FederatedPlannerUtils.isScalarLikeMatrix(hop)) {
			if (fTypeMap != null)
				fTypeMap.put(hop.getHopID(), FType.BROADCAST);
			FederatedRefedRegistry.remove(scopeId, hop.getHopID());
			String anchorLabel = (anchorHop != null) ? findAnchorLabel(anchorHop) : null;
			FederatedFoutMaterializeRegistry.register(scopeId, hop.getHopID(), anchorHopId, "BROADCAST", anchorLabel,
				anchorKey);
			return;
		}

		// If we only have an anchorKey, we can still choose between aligned upload and broadcast based on
		// vector axis / axis length mismatch inferred from the signature.
		if (anchorHop == null) {
			FType effective = anchorType != null ? anchorType : FType.FULL;
			effective = adjustCpFoutFTypeForAnchorKey(hop, effective);
			if (fTypeMap != null)
				fTypeMap.put(hop.getHopID(), effective);

			if (effective == FType.BROADCAST) {
				FederatedRefedRegistry.remove(scopeId, hop.getHopID());
				FederatedFoutMaterializeRegistry.register(scopeId, hop.getHopID(), anchorHopId, "BROADCAST", null,
					anchorKey);
				return;
			}

			if (FederatedFoutMaterializeRegistry.snapshot(scopeId).containsKey(hop.getHopID()))
				return;
			FederatedRefedRegistry.register(scopeId, hop.getHopID(), anchorHopId, anchorKey);
			return;
		}

		// Anchor hop available: preserve legacy dim/axis mismatch logic.
		if (hasDimMismatch(hop, anchorHop, fTypeMap)) {
			boolean broadcastMismatch = isVectorAxisMismatch(hop, anchorHop, fTypeMap)
				|| isMaterializeAxisMismatch(hop, anchorHop, fTypeMap);
			String fTypeHint = broadcastMismatch ? "BROADCAST" : toFTypeHint(getKnownFType(anchorHop, fTypeMap));
			String anchorLabel = findAnchorLabel(anchorHop);
			if (LOG.isDebugEnabled()) {
				long[] anchorDims = getAnchorDimsIfKnown(anchorHop);
				String anchorDimStr = (anchorDims != null) ? "(" + anchorDims[0] + "," + anchorDims[1] + ")"
					: "(unknown)";
				LOG.debug("CP->FOUT decision: MATERIALIZE (" + (broadcastMismatch ? "axis_mismatch" : "dim_mismatch")
					+ ") hopID=" + hop.getHopID() + " op=" + hop.getOpString() + " local=(" + hop.getDim1()
					+ "," + hop.getDim2() + ")" + " anchor=" + anchorHop.getHopID() + " anchorDims=" + anchorDimStr
					+ " fTypeHint=" + fTypeHint + " anchorLabel=" + anchorLabel);
			}
			if (broadcastMismatch && fTypeMap != null)
				fTypeMap.put(hop.getHopID(), FType.BROADCAST);
			else if (fTypeMap != null && anchorType != null)
				fTypeMap.put(hop.getHopID(), anchorType);
			FederatedRefedRegistry.remove(scopeId, hop.getHopID());
			FederatedFoutMaterializeRegistry.register(scopeId, hop.getHopID(), anchorHopId, fTypeHint, anchorLabel,
				anchorKey);
			return;
		}

		if (fTypeMap != null && anchorType != null)
			fTypeMap.put(hop.getHopID(), anchorType);

		if (FederatedFoutMaterializeRegistry.snapshot(scopeId).containsKey(hop.getHopID()))
			return;
		if (LOG.isDebugEnabled())
			LOG.debug("CP->FOUT decision: REFED hopID=" + hop.getHopID() + " op=" + hop.getOpString()
				+ " anchor=" + anchorHop.getHopID());
		FederatedRefedRegistry.register(scopeId, hop.getHopID(), anchorHopId, anchorKey);
	}

	private static AnchorSelection selectAnchor(Hop hop, java.util.Map<Long, FType> fTypeMap,
				boolean onlyFedParents, boolean throwOnFailure, AnchorSelection blockAnchor) {
		return selectAnchor(hop, fTypeMap, onlyFedParents, throwOnFailure, blockAnchor, new HashSet<>());
	}

	private static AnchorSelection selectAnchor(Hop hop, java.util.Map<Long, FType> fTypeMap,
				boolean onlyFedParents, boolean throwOnFailure, AnchorSelection blockAnchor, Set<Long> visited) {
		if (hop == null)
			return null;
		if (!visited.add(hop.getHopID()))
			return null;
		AnchorKey selectedKey = null;
		Hop selectedAnchor = null;
		boolean sawAnchorParent = false;
		boolean sawRequiredParent = false;
		boolean sawOptionalParent = false;
		final List<String> debug = throwOnFailure ? new ArrayList<>() : null;

		for (Hop parent : hop.getParent()) {
			if (parent == null)
				continue;
			ExecType exec = getPlannedExecType(parent);
			if (onlyFedParents) {
				if (exec != ExecType.FED && exec != null) {
					if (debug != null)
						debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString()
							+ "): onlyFedParents=true but plannedExec=" + exec
							+ " forcedExec=" + parent.getForcedExecType() + " exec=" + parent.getExecType());
					continue;
				}
			} else if (exec != null && exec != ExecType.FED) {
				if (debug != null)
					debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString()
						+ "): plannedExec=" + exec + " (non-FED)");
				continue;
			}
				int targetIndex = parent.getInput().indexOf(hop);
				if (targetIndex >= 0) {
					InputRequirement targetReq = classifyTargetRequirement(parent, hop, targetIndex, fTypeMap);
					if (targetReq == InputRequirement.AMBIGUOUS) {
						if (FederatedPlannerUtils.isScalarLikeMatrix(hop)) {
							targetReq = InputRequirement.REQUIRED;
						}
						if (shouldRelaxAmbiguousTargetRequirement(parent, hop, targetIndex, fTypeMap)) {
							targetReq = InputRequirement.REQUIRED;
						}
						else if (blockAnchor != null && !hasFederatedInput(parent, hop, fTypeMap)) {
							if (debug != null)
								debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString()
									+ "): targetReq=AMBIGUOUS at inputIndex=" + targetIndex
									+ " using blockAnchor=" + blockAnchor.anchorHop.getHopID());
							targetReq = InputRequirement.OPTIONAL;
						}
						else {
							if (throwOnFailure) {
								String detail = explainAmbiguousTargetRequirement(parent, hop, targetIndex, fTypeMap);
								if (debug != null)
									debug.add("fail parent=" + parent.getHopID() + " (" + parent.getOpString()
										+ "): targetReq=AMBIGUOUS at inputIndex=" + targetIndex
										+ " detail={" + detail + "}");
								LOG.error("CP->FOUT refed target ambiguous for hop " + hop.getHopID()
									+ " under parent " + parent.getHopID() + ": " + detail);
								throw new DMLRuntimeException("CP->FOUT refed cannot classify target input for hop "
											+ hop.getHopID() + " under parent " + parent.getHopID());
							}
							return null;
						}
					}
					if (targetReq == InputRequirement.REQUIRED)
						sawRequiredParent = true;
					if (targetReq == InputRequirement.OPTIONAL) {
						sawOptionalParent = true;
						if (debug != null)
							debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString()
								+ "): targetReq=OPTIONAL at inputIndex=" + targetIndex);
						continue;
					}
				}
			ParentAnchor parentAnchor = null;
			// IMPORTANT: for CP->FOUT anchoring we must rely on an already-existing runtime
			// federated map. Planned CP->FOUT candidates (cached or hinted via fTypeMap)
			// must not be used as anchors, otherwise we can create invalid/cyclic refed
			// chains where the "anchor" is still local at runtime.
			if (!hasRuntimeFederatedInputExcluding(parent, hop)) {
				parentAnchor = findConsumerAnchor(parent, hop, fTypeMap, blockAnchor, visited);
				if (parentAnchor == null) {
					if (debug != null)
						debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString()
							+ "): no federated inputs besides target");
					continue;
				}
			} else {
				try {
					parentAnchor = determineParentAnchor(parent, hop, fTypeMap,
							false, throwOnFailure, true);
				}
				catch (RuntimeException ex) {
					if (debug != null) {
						debug.add("fail parent=" + parent.getHopID() + " (" + parent.getOpString()
							+ "): determineParentAnchor threw " + ex.getClass().getSimpleName()
							+ ": " + ex.getMessage());
						LOG.error("CP->FOUT refed anchor selection failed for hop " + hop.getHopID()
							+ " (" + hop.getOpString() + "): " + String.join(" | ", debug), ex);
					}
					throw ex;
				}
			}
			if (parentAnchor == null)
				return null;
			if (parentAnchor.isEmpty()) {
				if (debug != null)
					debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString() + "): empty anchor");
				continue;
			}
			if (onlyFedParents && blockAnchor != null && blockAnchor.key != null
				&& parentAnchor.key != null && !anchorsCompatible(blockAnchor.key, parentAnchor.key)) {
				if (debug != null)
					debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString()
						+ "): anchorKey mismatch with required=" + blockAnchor.key);
				continue;
			}
			sawAnchorParent = true;

			if (selectedKey == null) {
				selectedKey = parentAnchor.key;
				selectedAnchor = parentAnchor.anchorHop;
				if (debug != null)
					debug.add("select parent=" + parent.getHopID() + " (" + parent.getOpString()
						+ "): anchorHop=" + (selectedAnchor != null ? selectedAnchor.getHopID() : -1)
						+ " anchorKey=" + selectedKey);
			} else if (!anchorsCompatible(selectedKey, parentAnchor.key)) {
				if (debug != null) {
					debug.add("fail parent=" + parent.getHopID() + " (" + parent.getOpString()
						+ "): anchorKey mismatch selected=" + selectedKey + " vs parent=" + parentAnchor.key
						+ " parentAnchorHop=" + (parentAnchor.anchorHop != null ? parentAnchor.anchorHop.getHopID() : -1));
					LOG.warn("CP->FOUT refed anchor mismatch for hop " + hop.getHopID()
						+ " (" + hop.getOpString() + "): " + String.join(" | ", debug)
						+ " | fallback=LOUT");
				}
				return null;
			}
		}

		if (!sawAnchorParent) {
			if (sawOptionalParent && !sawRequiredParent)
				return null;
			AnchorSelection inputAnchor = findInputAnchorSelection(hop, fTypeMap);
			if (inputAnchor != null)
				return inputAnchor;
			if (blockAnchor != null) {
				if (debug != null)
					debug.add("fallback blockAnchor=" + blockAnchor.anchorHop.getHopID()
						+ " anchorKey=" + blockAnchor.key);
				return blockAnchor;
			}
			if (throwOnFailure) {
				if (debug != null)
					LOG.warn("CP->FOUT refed missing FED anchor-parent for hop " + hop.getHopID()
						+ " (" + hop.getOpString() + "): " + String.join(" | ", debug)
						+ " | fallback=LOUT");
				return null;
			}
			return null;
		}
		if (selectedAnchor == null) {
			if (throwOnFailure) {
				if (debug != null)
					LOG.error("CP->FOUT refed could not determine anchor for hop " + hop.getHopID()
						+ " (" + hop.getOpString() + "): " + String.join(" | ", debug));
				throw new DMLRuntimeException("CP->FOUT refed cannot determine anchor for hop "
							+ hop.getHopID() + " (" + hop.getOpString() + ")");
			}
			return null;
		}
		return new AnchorSelection(selectedKey, selectedAnchor);
	}

	private static ParentAnchor findConsumerAnchor(Hop parent, Hop target, java.util.Map<Long, FType> fTypeMap,
			AnchorSelection blockAnchor, Set<Long> visited) {
		if (parent == null || target == null)
			return null;
		if (!canPropagateAnchorFromConsumers(parent))
			return null;
		List<Hop> parents = parent.getParent();
		if (parents == null || parents.isEmpty()) {
			if (blockAnchor != null && blockAnchor.key != null)
				return new ParentAnchor(blockAnchor.key, blockAnchor.anchorHop);
			return null;
		}
		AnchorKey selectedKey = null;
		Hop selectedAnchor = null;
		Deque<Hop> queue = new ArrayDeque<>(parents);
		while (!queue.isEmpty()) {
			Hop consumer = queue.poll();
			if (consumer == null)
				continue;
			if (!visited.add(consumer.getHopID()))
				continue;
			ExecType exec = getPlannedExecType(consumer);
			if (exec == ExecType.FED) {
				ParentAnchor anchor = determineParentAnchor(consumer, target, fTypeMap,
						false, false, true);
				if (anchor != null && !anchor.isEmpty()) {
					if (selectedKey == null) {
						selectedKey = anchor.key;
						selectedAnchor = anchor.anchorHop;
					} else if (!anchorsCompatible(selectedKey, anchor.key)) {
						return null;
					}
				}
			}
			if (canPropagateAnchorFromConsumers(consumer)) {
				List<Hop> grandparents = consumer.getParent();
				if (grandparents != null)
					queue.addAll(grandparents);
			}
		}
		if (selectedKey != null && selectedAnchor != null)
			return new ParentAnchor(selectedKey, selectedAnchor);
		if (blockAnchor != null && blockAnchor.key != null)
			return new ParentAnchor(blockAnchor.key, blockAnchor.anchorHop);
		return null;
	}

	private static boolean canPropagateAnchorFromConsumers(Hop hop) {
		if (hop == null)
			return false;
		if (hop instanceof ReorgOp)
			return true;
		if (hop instanceof UnaryOp)
			return ((UnaryOp) hop).getOp() != OpOp1.BROADCAST;
		return false;
	}

	private static AnchorSelection buildBlockAnchorSelection(List<Hop> hops, java.util.Map<Long, FType> fTypeMap,
			java.util.Map<String, String> runtimeSignatures) {
		if (hops == null || hops.isEmpty())
			return null;
		AnchorKey selectedKey = null;
		Hop selectedAnchor = null;
		for (Hop hop : hops) {
			String runtimeSig = getRuntimeSignature(hop, runtimeSignatures);
			if (runtimeSig == null) {
				// Restrict block anchors to stable federated data objects (fed-init reads),
				// excluding transient writes and intermediate federated computations.
				if (!(hop instanceof DataOp))
					continue;
				DataOp dataOp = (DataOp) hop;
				OpOpData op = dataOp.getOp();
				if (op != OpOpData.TRANSIENTREAD && op != OpOpData.FEDERATED)
					continue;
				boolean isFedInitVar = op == OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.isFedInitVar(dataOp.getName());
				boolean hasVarAnchor = op == OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.getFedAnchorKey(dataOp.getName()) != null;
				boolean isFedExecRead = op == OpOpData.TRANSIENTREAD && getPlannedExecType(hop) == ExecType.FED;
				boolean isFedInput = isFederatedInput(hop, fTypeMap);
				if (op == OpOpData.TRANSIENTREAD && !isFedInitVar && !hasVarAnchor && !isFedExecRead && !isFedInput)
					continue;
				if (!isBlockAnchorCandidate(hop))
					continue;
				if (findFedInitSignature(hop) == null && !hasVarAnchor && !isFedExecRead && !isFedInput)
					continue;
			}
			if (runtimeSig == null && !isFederatedInput(hop, fTypeMap))
				continue;
			FType fType = getKnownFType(hop, fTypeMap);
			AnchorKey key = (runtimeSig != null)
				? buildAnchorKeyFromSignature(runtimeSig, fType)
				: buildAnchorKey(hop, fTypeMap);
			if (key == null)
				continue;
			if (selectedKey == null) {
				selectedKey = key;
				selectedAnchor = hop;
			} else if (!anchorsCompatible(selectedKey, key)) {
				return null;
			}
		}
		if (selectedKey == null || selectedAnchor == null)
			return null;
		return new AnchorSelection(selectedKey, selectedAnchor);
	}

	private static boolean isBlockAnchorCandidate(Hop hop) {
		if (hop == null)
			return false;
		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTWRITE)
				return false;
		}
		ExecType exec = getPlannedExecType(hop);
		if (exec == ExecType.FED)
			return true;
		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED)
				return true;
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.isFedInitVar(dataOp.getName()))
				return true;
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.getFedAnchorKey(dataOp.getName()) != null)
				return true;
		}
		return false;
	}

	private static AnchorKey buildAnchorKeyFromSignature(String signature, FType fType) {
		if (signature == null)
			return null;
		String sig = signature;
		if (fType != null)
			sig = sig + "|" + fType.name();
		return new AnchorKey(AnchorKeyType.FEDINIT_SIGNATURE, sig);
	}

	private static String toAnchorKeyString(AnchorSelection selection) {
		if (selection == null || selection.key == null)
			return null;
		if (selection.key.type != AnchorKeyType.FEDINIT_SIGNATURE)
			return null;
		if (selection.key.value instanceof String)
			return (String) selection.key.value;
		return null;
	}

	private static AnchorKey selectGlobalAnchorKey(java.util.Map<Long, FType> fTypeMap) {
		String varName = FederatedPlannerUtils.getUniqueFedInitVarName();
		if (varName == null || varName.isEmpty())
			return null;
		String anchorKey = FederatedPlannerUtils.getFedAnchorKey(varName);
		if (isNonVarAnchorKey(anchorKey))
			return new AnchorKey(AnchorKeyType.FEDINIT_SIGNATURE, anchorKey);
		String signature = FederatedPlannerUtils.getFedInitSignature(varName);
		FType fType = FederatedPlannerUtils.getFedInitFType(varName);
		AnchorKey key = buildAnchorKeyFromSignature(signature, fType);
		if (key != null && !isVarAnchor(key))
			return key;
		return null;
	}

	private static AnchorSelection buildSyntheticAnchorSelection(List<Hop> all, java.util.Map<Long, FType> fTypeMap,
			java.util.Map<String, String> runtimeSignatures,
			java.util.Map<String, FType> runtimeTypes) {
		if (runtimeSignatures == null || runtimeSignatures.isEmpty())
			return null;
		FType preferredType = FType.ROW;
		String signature = selectUniqueSignature(runtimeSignatures, runtimeTypes, preferredType);
		if (signature == null) {
			preferredType = FType.COL;
			signature = selectUniqueSignature(runtimeSignatures, runtimeTypes, preferredType);
		}
		if (signature == null) {
			preferredType = FType.FULL;
			signature = selectUniqueSignature(runtimeSignatures, runtimeTypes, preferredType);
		}
		String anchorVar = null;
		if (signature != null)
			anchorVar = selectVarForSignature(runtimeSignatures, runtimeTypes, preferredType, signature);
		if (signature == null || anchorVar == null || anchorVar.isEmpty()) {
			// Best-effort fallback: pick the first runtime federated variable to anchor CP->FOUT
			// when multiple signatures are present in the same block.
			for (java.util.Map.Entry<String, String> entry : runtimeSignatures.entrySet()) {
				if (entry.getValue() == null)
					continue;
				signature = entry.getValue();
				anchorVar = entry.getKey();
				break;
			}
			if (signature == null || anchorVar == null || anchorVar.isEmpty())
				return null;
			FType runtimeType = (runtimeTypes != null) ? runtimeTypes.get(anchorVar) : null;
			if (runtimeType != null)
				preferredType = runtimeType;
		}

		Hop anchorHop = findHopByName(all, anchorVar);
		if (anchorHop == null) {
			int blen = ConfigurationManager.getBlocksize();
			anchorHop = new DataOp(anchorVar, DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, anchorVar, -1, -1, -1, blen);
		}
		FType fType = (runtimeTypes != null) ? runtimeTypes.get(anchorVar) : preferredType;
		if (fType != null && fTypeMap != null)
			fTypeMap.put(anchorHop.getHopID(), fType);
		AnchorKey key = buildAnchorKeyFromSignature(signature, fType);
		return new AnchorSelection(key, anchorHop);
	}

	private static String selectUniqueSignature(java.util.Map<String, String> runtimeSignatures,
			java.util.Map<String, FType> runtimeTypes, FType desiredType) {
		if (runtimeSignatures == null || runtimeSignatures.isEmpty())
			return null;
		String signature = null;
		for (java.util.Map.Entry<String, String> entry : runtimeSignatures.entrySet()) {
			String sig = entry.getValue();
			if (sig == null)
				continue;
			if (runtimeTypes != null && desiredType != null) {
				FType fType = runtimeTypes.get(entry.getKey());
				if (fType != desiredType)
					continue;
			}
			if (signature == null)
				signature = sig;
			else if (!signature.equals(sig))
				return null;
		}
		return signature;
	}

	private static String selectVarForSignature(java.util.Map<String, String> runtimeSignatures,
			java.util.Map<String, FType> runtimeTypes, FType desiredType, String signature) {
		if (runtimeSignatures == null || runtimeSignatures.isEmpty() || signature == null)
			return null;
		for (java.util.Map.Entry<String, String> entry : runtimeSignatures.entrySet()) {
			if (!signature.equals(entry.getValue()))
				continue;
			if (runtimeTypes != null && desiredType != null) {
				FType fType = runtimeTypes.get(entry.getKey());
				if (fType != desiredType)
					continue;
			}
			return entry.getKey();
		}
		return null;
	}

	private static Hop findHopByName(List<Hop> hops, String name) {
		if (hops == null || name == null)
			return null;
		for (Hop hop : hops) {
			if (hop instanceof DataOp && name.equals(((DataOp) hop).getName()))
				return hop;
		}
		return null;
	}

	private static String getRuntimeSignature(Hop hop, java.util.Map<String, String> runtimeSignatures) {
		if (hop == null || runtimeSignatures == null || runtimeSignatures.isEmpty())
			return null;
		if (!(hop instanceof DataOp))
			return null;
		DataOp dataOp = (DataOp) hop;
		if (dataOp.getOp() != org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD)
			return null;
		return runtimeSignatures.get(dataOp.getName());
	}

	private static InputRequirement classifyTargetRequirement(Hop parent, Hop target, int targetIndex,
			java.util.Map<Long, FType> fTypeMap) {
		InputRequirement base = classifyRequiredInput(parent, target, targetIndex);
		if (base == InputRequirement.OPTIONAL && FederatedPlannerUtils.isScalarLikeMatrix(target))
			base = InputRequirement.REQUIRED;
		if (base == InputRequirement.AMBIGUOUS)
			base = resolveVectorVectorRequirement(parent, target, targetIndex, fTypeMap);
		if (base != InputRequirement.REQUIRED)
			return base;
		if (target != null && target.getDataType().isMatrix()) {
			FType targetFType = getKnownFType(target, fTypeMap);
			if (targetFType == FType.PART || targetFType == FType.OTHER)
				return InputRequirement.AMBIGUOUS;
			if (targetFType == FType.BROADCAST)
				return InputRequirement.REQUIRED;
			if (targetFType == FType.FULL && isBroadcastableFullInput(parent, target, fTypeMap))
				return InputRequirement.OPTIONAL;
		}
		return base;
	}

	private static ParentAnchor determineParentAnchor(Hop parent, Hop target,
			java.util.Map<Long, FType> fTypeMap, boolean treatFTypeMapAsPlannedFederatedInputs,
			boolean throwOnFailure, boolean allowEmpty) {
		List<InputCandidate> candidates = new ArrayList<>();
		boolean hasPartitioned = false;
		List<Hop> inputs = parent.getInput();
		for (int i = 0; i < inputs.size(); i++) {
			Hop input = inputs.get(i);
			if (input == null || input == target)
				continue;
			// Disallow anchors that depend on the CP->FOUT target (would create a cyclic dependency
			// once the refed/materialize lop is inserted between target and its FED parents).
			if (dependsOn(input, target))
				continue;
			InputRequirement req = classifyInput(parent, input, i, fTypeMap);
			if (req == InputRequirement.AMBIGUOUS) {
				if (shouldRelaxAmbiguousTargetRequirement(parent, input, i, fTypeMap)) {
					req = InputRequirement.REQUIRED;
				}
				else if (throwOnFailure) {
					LOG.error("CP->FOUT refed parent input ambiguous for hop " + target.getHopID()
						+ " under parent " + parent.getHopID() + ": "
						+ explainAmbiguousTargetRequirement(parent, input, i, fTypeMap));
					throw new DMLRuntimeException("CP->FOUT refed cannot determine federated-required input for hop "
							+ target.getHopID() + " under parent " + parent.getHopID());
				}
				else {
					return null;
				}
			}
			if (req == InputRequirement.OPTIONAL)
				continue;
			// Only accept anchors that are federated at runtime (have an actual FederationMap).
			// Do not accept planned CP->FOUT candidates as anchors, because they may still be
			// local at the point where the refed/materialize is executed.
			boolean runtimeFed = isRuntimeFederatedInput(input, null, null);
			boolean plannedFed = treatFTypeMapAsPlannedFederatedInputs
					&& fTypeMap != null
					&& fTypeMap.get(input.getHopID()) != null;
			if (!runtimeFed && !plannedFed)
				continue;
			FType fType = getKnownFType(input, fTypeMap);
			if (fType == null) {
				// If the anchor input is federated but its logical FType is unknown (e.g., intermediate
				// FED output without propagated type), still accept it as an anchor candidate.
				// Default to a safe type for candidate bookkeeping; the runtime anchor carries the
				// concrete FederationMap (and thus effective type and worker pool).
				FType axis = FederatedPlannerUtils.getVectorAxis(input);
				fType = (axis != null) ? axis : FType.FULL;
			}
			hasPartitioned |= (fType == FType.ROW || fType == FType.COL);
			candidates.add(new InputCandidate(input, fType));
		}

		if (hasPartitioned) {
			candidates.removeIf(c -> c.fType == FType.FULL);
		}

		AnchorKey parentKey = null;
		Hop parentAnchor = null;
		for (InputCandidate cand : candidates) {
			AnchorKey key = buildAnchorKey(cand.hop, fTypeMap);
			if (key == null) {
				if (cand.hop instanceof DataOp) {
					DataOp dataOp = (DataOp) cand.hop;
					if (dataOp.getOp() == OpOpData.TRANSIENTREAD)
						continue;
				}
				ExecType candExec = getPlannedExecType(cand.hop);
				if (candExec == ExecType.CP) {
					continue;
				}
				if (throwOnFailure)
					throw new DMLRuntimeException("CP->FOUT refed requires federated input anchor for hop "
							+ target.getHopID() + " under parent " + parent.getHopID());
				return null;
			}
			if (parentKey == null) {
				parentKey = key;
				parentAnchor = cand.hop;
			} else if (!anchorsCompatible(parentKey, key)) {
				if (throwOnFailure)
					throw new DMLRuntimeException("CP->FOUT refed anchor ambiguity for hop "
							+ target.getHopID() + " under parent " + parent.getHopID());
				return null;
			}
		}

		if (parentKey == null) {
			if (allowEmpty)
				return ParentAnchor.empty();
			if (throwOnFailure)
				throw new DMLRuntimeException("CP->FOUT refed cannot determine anchor for hop "
						+ target.getHopID() + " under parent " + parent.getHopID());
			return null;
		}
		return new ParentAnchor(parentKey, parentAnchor);
	}

	private static boolean dependsOn(Hop candidate, Hop target) {
		if (candidate == null || target == null)
			return false;
		Set<Hop> visited = new HashSet<>();
		Deque<Hop> queue = new ArrayDeque<>();
		queue.add(candidate);
		while (!queue.isEmpty()) {
			Hop cur = queue.poll();
			if (cur == null || !visited.add(cur))
				continue;
			if (cur == target)
				return true;
			List<Hop> ins = cur.getInput();
			if (ins != null)
				queue.addAll(ins);
		}
		return false;
	}

	private static InputRequirement classifyInput(Hop parent, Hop input, int index,
			java.util.Map<Long, FType> fTypeMap) {
		if (input == null)
			return InputRequirement.OPTIONAL;
		if (!input.getDataType().isMatrix())
			return InputRequirement.OPTIONAL;
		if (FederatedPlannerUtils.isScalarLikeMatrix(input))
			return InputRequirement.OPTIONAL;
		if (!isFederatedInput(input, fTypeMap))
			return InputRequirement.OPTIONAL;
		InputRequirement baseReq = classifyRequiredInput(parent, input, index);
		if (baseReq == InputRequirement.AMBIGUOUS)
			baseReq = resolveVectorVectorRequirement(parent, input, index, fTypeMap);
		if (baseReq == InputRequirement.AMBIGUOUS)
			return InputRequirement.AMBIGUOUS;
		if (baseReq == InputRequirement.OPTIONAL)
			return InputRequirement.OPTIONAL;

		FType fType = getKnownFType(input, fTypeMap);
		// If the planning-time FType is unknown but the input is federated, still treat it as a
		// viable REQUIRED anchor. The runtime anchor carries the concrete FederationMap (and thus
		// its effective type), while intermediate FED outputs may not always have an inferred FType.
		if (fType == null)
			return InputRequirement.REQUIRED;
		if (fType == FType.PART || fType == FType.OTHER)
			return InputRequirement.AMBIGUOUS;
		if (fType == FType.BROADCAST)
			return InputRequirement.OPTIONAL;
		if (fType == FType.FULL && isBroadcastableFullInput(parent, input, fTypeMap))
			return InputRequirement.OPTIONAL;
		return InputRequirement.REQUIRED;
	}

	/**
	 * Planner-side input requirement classification for FED execution.
	 *
	 * <p>This variant does <b>not</b> require the input to be already federated. It answers:
	 * "If the parent executes in FED, does this input need to be federated (REQUIRED),
	 * can it be optional/broadcast (OPTIONAL), or is it ambiguous (AMBIGUOUS)?"</p>
	 */
	public static InputRequirement getInputRequirementForFedExec(Hop parent, Hop input, int index,
			java.util.Map<Long, FType> fTypeMap) {
		if (input == null)
			return InputRequirement.OPTIONAL;
		if (!input.getDataType().isMatrix())
			return InputRequirement.OPTIONAL;
		if (FederatedPlannerUtils.isScalarLikeMatrix(input))
			return InputRequirement.OPTIONAL;
		InputRequirement baseReq = classifyRequiredInput(parent, input, index);
		if (baseReq == InputRequirement.AMBIGUOUS)
			baseReq = resolveVectorVectorRequirement(parent, input, index, fTypeMap);
		if (baseReq == InputRequirement.AMBIGUOUS)
			return InputRequirement.AMBIGUOUS;
		if (baseReq == InputRequirement.OPTIONAL)
			return InputRequirement.OPTIONAL;

		FType fType = getKnownFType(input, fTypeMap);
		// Unknown type: treat as REQUIRED for safety.
		if (fType == null)
			return InputRequirement.REQUIRED;
		if (fType == FType.PART || fType == FType.OTHER)
			return InputRequirement.AMBIGUOUS;
		if (fType == FType.BROADCAST)
			return InputRequirement.OPTIONAL;
		if (fType == FType.FULL && isBroadcastableFullInput(parent, input, fTypeMap))
			return InputRequirement.OPTIONAL;
		return InputRequirement.REQUIRED;
	}

	private static InputRequirement classifyRequiredInput(Hop parent, Hop input, int index) {
		if (parent == null)
			return InputRequirement.AMBIGUOUS;
		if (parent instanceof IndexingOp)
			return index == 0 ? InputRequirement.REQUIRED : InputRequirement.OPTIONAL;
		if (parent instanceof ParameterizedBuiltinOp)
			return (input != null && input.getDataType() != null && input.getDataType().isMatrix())
				? InputRequirement.REQUIRED
				: InputRequirement.OPTIONAL;
		if (parent instanceof QuaternaryOp) {
			// Quaternary FED instructions anchor on the primary matrix input (index 0);
			// remaining matrix inputs may be broadcast/aligned as needed at runtime.
			if (index == 0)
				return InputRequirement.REQUIRED;
			return (input != null && input.getDataType() != null && input.getDataType().isMatrix())
				? InputRequirement.REQUIRED
				: InputRequirement.OPTIONAL;
		}
		if (parent instanceof TernaryOp) {
			TernaryOp ternary = (TernaryOp) parent;
			OpOp3 op = ternary.getOp();
			if (op == null) {
				return (index <= 2) ? InputRequirement.REQUIRED : InputRequirement.OPTIONAL;
			}
			switch (op) {
				case CTABLE:
					// (x, y, w, [dim1, dim2, dim3]) -> dimension inputs are optional.
					return (index <= 2) ? InputRequirement.REQUIRED : InputRequirement.OPTIONAL;
				case QUANTILE:
				case INTERQUANTILE:
				case MOMENT:
				case COV:
					// Primary input is required; auxiliary matrix inputs may be present but are safe as optional.
					return (index == 0) ? InputRequirement.REQUIRED : InputRequirement.OPTIONAL;
				case IFELSE:
				case PLUS_MULT:
				case MINUS_MULT:
				case MAP:
				default:
					return (index <= 2) ? InputRequirement.REQUIRED : InputRequirement.OPTIONAL;
			}
		}
		if (parent instanceof UnaryOp)
			return ((UnaryOp) parent).getOp() == OpOp1.BROADCAST
					? InputRequirement.OPTIONAL
					: InputRequirement.REQUIRED;
		if (parent instanceof AggUnaryOp)
			return InputRequirement.REQUIRED;
		if (parent instanceof BinaryOp)
			return classifyBinaryLikeInput(parent, input, index);
		if (parent instanceof AggBinaryOp)
			return classifyBinaryLikeInput(parent, input, index);
		if (parent instanceof NaryOp)
			return classifyNaryLikeInput(parent, input, index);
		if (parent instanceof ReorgOp)
			return InputRequirement.REQUIRED;
		return InputRequirement.OPTIONAL;
	}

	private static InputRequirement classifyBinaryLikeInput(Hop parent, Hop input, int index) {
		List<Hop> inputs = parent.getInput();
		if (inputs == null || inputs.size() < 2)
			return InputRequirement.AMBIGUOUS;
		int otherIndex = (index == 0) ? 1 : 0;
		if (otherIndex >= inputs.size())
			return InputRequirement.AMBIGUOUS;
		Hop other = inputs.get(otherIndex);
		if (other == null || !other.getDataType().isMatrix())
			return InputRequirement.REQUIRED;
		ShapeClass thisShape = getShapeClass(input);
		ShapeClass otherShape = getShapeClass(other);
		if (thisShape == ShapeClass.UNKNOWN || otherShape == ShapeClass.UNKNOWN)
			return InputRequirement.AMBIGUOUS;
		if (thisShape == ShapeClass.VECTOR && otherShape == ShapeClass.MATRIX)
			return InputRequirement.OPTIONAL;
		if (thisShape == ShapeClass.MATRIX && otherShape == ShapeClass.VECTOR)
			return InputRequirement.REQUIRED;
		if (thisShape == ShapeClass.MATRIX && otherShape == ShapeClass.MATRIX)
			return InputRequirement.REQUIRED;
		if (thisShape == ShapeClass.VECTOR && otherShape == ShapeClass.VECTOR && parent instanceof AggBinaryOp) {
			long thisRows = input.getDim1();
			long thisCols = input.getDim2();
			long otherRows = other.getDim1();
			long otherCols = other.getDim2();
			if (thisRows > 0 && thisCols > 0 && otherRows > 0 && otherCols > 0) {
				if (thisRows == 1 && otherCols == 1 && otherRows > 1)
					return InputRequirement.OPTIONAL;
				if (thisCols == 1 && otherRows == 1 && thisRows > 1)
					return InputRequirement.REQUIRED;
			}
		}
		return InputRequirement.AMBIGUOUS;
	}

	private static InputRequirement classifyNaryLikeInput(Hop parent, Hop input, int index) {
		if (!(parent instanceof NaryOp))
			return InputRequirement.AMBIGUOUS;
		NaryOp nary = (NaryOp) parent;
		if (!nary.getOp().isCellOp())
			return InputRequirement.AMBIGUOUS;
		if (input == null || !input.getDataType().isMatrix())
			return InputRequirement.OPTIONAL;
		ShapeClass thisShape = getShapeClass(input);
		if (thisShape == ShapeClass.UNKNOWN)
			return InputRequirement.AMBIGUOUS;
		List<Hop> inputs = parent.getInput();
		if (inputs == null || inputs.size() < 2)
			return InputRequirement.AMBIGUOUS;
		boolean anyMatrix = false;
		boolean anyVector = false;
		for (int i = 0; i < inputs.size(); i++) {
			if (i == index)
				continue;
			Hop other = inputs.get(i);
			if (other == null || !other.getDataType().isMatrix())
				continue;
			ShapeClass otherShape = getShapeClass(other);
			if (otherShape == ShapeClass.UNKNOWN)
				return InputRequirement.AMBIGUOUS;
			if (otherShape == ShapeClass.MATRIX)
				anyMatrix = true;
			else if (otherShape == ShapeClass.VECTOR)
				anyVector = true;
		}
		if (thisShape == ShapeClass.MATRIX)
			return InputRequirement.REQUIRED;
		if (anyMatrix)
			return InputRequirement.OPTIONAL;
		if (anyVector)
			return InputRequirement.AMBIGUOUS;
		return InputRequirement.REQUIRED;
	}

	private static InputRequirement resolveVectorVectorRequirement(Hop parent, Hop input, int index,
			java.util.Map<Long, FType> fTypeMap) {
		if (!(parent instanceof BinaryOp || parent instanceof AggBinaryOp || parent instanceof NaryOp))
			return InputRequirement.AMBIGUOUS;
		if (parent instanceof NaryOp && !((NaryOp) parent).getOp().isCellOp())
			return InputRequirement.AMBIGUOUS;
		if (input == null || !input.getDataType().isMatrix())
			return InputRequirement.AMBIGUOUS;
		List<Hop> inputs = parent.getInput();
		if (inputs == null || inputs.size() < 2)
			return InputRequirement.AMBIGUOUS;
		ShapeClass thisShape = getShapeClass(input);
		if (thisShape != ShapeClass.VECTOR)
			return InputRequirement.AMBIGUOUS;
		boolean anyFed = isFederatedInput(input, fTypeMap);
		boolean sawVector = false;
		for (int i = 0; i < inputs.size(); i++) {
			if (i == index)
				continue;
			Hop other = inputs.get(i);
			if (other == null || !other.getDataType().isMatrix())
				continue;
			ShapeClass otherShape = getShapeClass(other);
			if (otherShape == ShapeClass.MATRIX)
				return InputRequirement.AMBIGUOUS;
			if (otherShape != ShapeClass.VECTOR)
				return InputRequirement.AMBIGUOUS;
			if (!vectorDimsMatch(input, other))
				return InputRequirement.AMBIGUOUS;
			sawVector = true;
			if (isFederatedInput(other, fTypeMap))
				anyFed = true;
		}
		if (!sawVector)
			return InputRequirement.AMBIGUOUS;
		return anyFed ? InputRequirement.REQUIRED : InputRequirement.OPTIONAL;
	}

	private static ShapeClass getShapeClass(Hop hop) {
		if (hop == null || !hop.getDataType().isMatrix())
			return ShapeClass.UNKNOWN;
		long rlen = hop.getDim1();
		long clen = hop.getDim2();
		if (rlen == 1 || clen == 1)
			return ShapeClass.VECTOR;
		if (rlen > 1 && clen > 1)
			return ShapeClass.MATRIX;
		return ShapeClass.UNKNOWN;
	}

	private static boolean vectorDimsMatch(Hop left, Hop right) {
		if (left == null || right == null)
			return false;
		long lRows = left.getDim1();
		long lCols = left.getDim2();
		long rRows = right.getDim1();
		long rCols = right.getDim2();
		if (lRows < 0 || lCols < 0 || rRows < 0 || rCols < 0)
			return false;
		return lRows == rRows && lCols == rCols;
	}

	private static boolean shouldRelaxAmbiguousTargetRequirement(Hop parent, Hop target, int targetIndex,
			java.util.Map<Long, FType> fTypeMap) {
		if (!(parent instanceof BinaryOp || parent instanceof AggBinaryOp))
			return false;
		if (target == null || !target.getDataType().isMatrix())
			return false;
		FType targetFType = getKnownFType(target, fTypeMap);
		if (targetFType == FType.PART || targetFType == FType.OTHER)
			return false;
		List<Hop> inputs = parent.getInput();
		int otherIndex = (targetIndex == 0) ? 1 : 0;
		if (inputs == null || otherIndex < 0 || otherIndex >= inputs.size())
			return false;
		Hop other = inputs.get(otherIndex);
		if (other == null || !other.getDataType().isMatrix())
			return false;
		long targetRows = target.getDim1();
		long targetCols = target.getDim2();
		boolean targetUnknown = (targetRows < 0 || targetCols < 0)
			&& getShapeClass(target) == ShapeClass.UNKNOWN;
		long otherRows = other.getDim1();
		long otherCols = other.getDim2();
		boolean otherUnknown = (otherRows < 0 || otherCols < 0)
			&& getShapeClass(other) == ShapeClass.UNKNOWN;
		return targetUnknown || otherUnknown;
	}

	private static String explainAmbiguousTargetRequirement(Hop parent, Hop target, int targetIndex,
			java.util.Map<Long, FType> fTypeMap) {
		StringBuilder sb = new StringBuilder();
		sb.append("parentOp=").append(parent != null ? parent.getOpString() : "null");
		sb.append(" parentClass=").append(parent != null ? parent.getClass().getSimpleName() : "null");
		sb.append(" baseReq=").append(classifyRequiredInput(parent, target, targetIndex));
		sb.append(" targetDims=(").append(target != null ? target.getDim1() : -1)
			.append(",").append(target != null ? target.getDim2() : -1).append(")");
		sb.append(" targetShape=").append(getShapeClass(target));
		sb.append(" targetFType=").append(getKnownFType(target, fTypeMap));
		sb.append(" hasFederatedInput=").append(hasFederatedInput(parent, target, fTypeMap));
		if (target != null && target.getInput() != null && !target.getInput().isEmpty()) {
			sb.append(" targetInputs=[");
			boolean first = true;
			for (Hop in : target.getInput()) {
				if (!first)
					sb.append(", ");
				first = false;
				if (in == null) {
					sb.append("null");
					continue;
				}
				sb.append(in.getHopID()).append(":")
					.append(in.getOpString()).append(":")
					.append(in.getDataType()).append(":")
					.append(in.getDim1()).append("x").append(in.getDim2());
			}
			sb.append("]");
		}
		List<Hop> inputs = parent != null ? parent.getInput() : null;
		if (inputs != null && inputs.size() > 1) {
			int otherIndex = (targetIndex == 0) ? 1 : 0;
			if (otherIndex < inputs.size()) {
				Hop other = inputs.get(otherIndex);
				sb.append(" otherIndex=").append(otherIndex);
				sb.append(" otherHop=").append(other != null ? other.getHopID() : -1);
				sb.append(" otherDims=(").append(other != null ? other.getDim1() : -1)
					.append(",").append(other != null ? other.getDim2() : -1).append(")");
				sb.append(" otherShape=").append(getShapeClass(other));
			}
		}
		return sb.toString();
	}

	private static boolean isFederatedInput(Hop hop) {
		return isFederatedInput(hop, null);
	}

	private static boolean isFederatedInput(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (hop == null)
			return false;
		if (isFederatedInitDataOp(hop))
			return true;
		if (fTypeMap != null && fTypeMap.get(hop.getHopID()) != null) {
			// Planned FOUT outputs (from DP/MinST enumeration) should be treated as federated
			// inputs for downstream feasibility checks. Do NOT do this for transient reads,
			// because TR locals may carry FType hints without a runtime FederationMap.
			// Also require that the hop is planned to produce a federated output; local outputs
			// may still carry CP->FOUT hints and must NOT be treated as federated sources.
			if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD) {
				if (!hop.hasLocalOutput())
					return true;
			}
		}

		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			OpOpData op = dataOp.getOp();
			if (op == org.apache.sysds.common.Types.OpOpData.FEDERATED)
				return true;
			if (op == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD) {
				String name = dataOp.getName();
				if (name != null && FederatedPlannerUtils.isFedInitVar(name))
					return true;
				// Only treat transient reads as federated if they are planned as FED/FOUT.
				ExecType exec = getPlannedExecType(hop);
				if (exec == ExecType.FED && !hop.hasLocalOutput()
					&& name != null && FederatedPlannerUtils.getFedAnchorKey(name) != null)
					return true;
				// For transient reads, only treat them as federated sources if we have an
				// explicit runtime anchor (fed-init var or a propagated anchorKey). Do NOT
				// use inferred logical FTypes as a proxy for "has FederationMap", because
				// local CP/LOUT values may also carry FType hints for CP->FOUT decisions.
				return false;
			}
		}

		ExecType exec = getPlannedExecType(hop);
		if (exec == ExecType.FED && !hop.hasLocalOutput()) {
			// If the planner forces a federated output (FOUT), treat it as federated regardless
			// of whether any input is already federated (e.g., local inputs broadcast to workers).
			if (hop.hasFederatedOutput())
				return true;
			// Otherwise require at least one federated input to establish runtime federation.
			return hasFederatedInput(hop, fTypeMap);
		}
		return false;
	}

	private static boolean isFederatedInitDataOp(Hop hop) {
		if (!(hop instanceof DataOp))
			return false;
		DataOp dataOp = (DataOp) hop;
		if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED)
			return true;
		return dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
			&& FederatedPlannerUtils.isFedInitVar(dataOp.getName());
	}

	private static ExecType getPlannedExecType(Hop hop) {
		if (hop == null)
			return null;
		ExecType forced = hop.getForcedExecType();
		return forced != null ? forced : hop.getExecType();
	}

	private static boolean isBroadcastableFullInput(Hop parent, Hop input, java.util.Map<Long, FType> fTypeMap) {
		if (parent == null || input == null || !input.getDataType().isMatrix())
			return false;
		if (!(parent instanceof BinaryOp || parent instanceof AggBinaryOp))
			return false;
		return hasPartitionedSibling(parent, input, fTypeMap);
	}

	private static boolean hasPartitionedSibling(Hop parent, Hop skip, java.util.Map<Long, FType> fTypeMap) {
		if (parent == null)
			return false;
		List<Hop> inputs = parent.getInput();
		if (inputs == null)
			return false;
		for (Hop input : inputs) {
			if (input == null || input == skip)
				continue;
			FType fType = getKnownFType(input, fTypeMap);
			if (fType == FType.ROW || fType == FType.COL)
				return true;
		}
		return false;
	}

	private static boolean hasFederatedInput(Hop parent, java.util.Map<Long, FType> fTypeMap) {
		return hasFederatedInput(parent, null, fTypeMap);
	}

	private static boolean hasFederatedInput(Hop parent, Hop skip, java.util.Map<Long, FType> fTypeMap) {
		if (parent == null)
			return false;
		List<Hop> inputs = parent.getInput();
		if (inputs == null)
			return false;
		for (Hop input : inputs) {
			if (input == null || input == skip)
				continue;
			if (isFederatedInput(input, fTypeMap))
				return true;
		}
		return false;
	}

	private static boolean hasRuntimeFederatedInputExcluding(Hop parent, Hop skip) {
		if (parent == null)
			return false;
		List<Hop> inputs = parent.getInput();
		if (inputs == null)
			return false;
		for (Hop input : inputs) {
			if (input == null || input == skip)
				continue;
			if (isRuntimeFederatedInput(input, null, null))
				return true;
		}
		return false;
	}

	private static boolean isPlannedFederatedInput(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (hop == null)
			return false;
		if (isFederatedInitDataOp(hop))
			return true;
		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			OpOpData op = dataOp.getOp();
			if (op == OpOpData.FEDERATED)
				return true;
			if (op == OpOpData.TRANSIENTREAD) {
				String name = dataOp.getName();
				if (name != null && (FederatedPlannerUtils.isFedInitVar(name)
					|| isNonVarAnchorKey(FederatedPlannerUtils.getFedAnchorKey(name))))
					return true;
			}
		}

		ExecType exec = getPlannedExecType(hop);
		if (exec != ExecType.FED || hop.hasLocalOutput())
			return false;
		return hasFederatedInput(hop, fTypeMap);
	}

	private static boolean anchorsCompatible(AnchorKey left, AnchorKey right) {
		if (left == null || right == null)
			return true;
		if (left.equals(right))
			return true;
		// Variable anchors are best-effort identifiers (var name, optional axis). We allow them to
		// match to avoid overly constraining planning when only variable-level identity is available.
		if (isVarAnchor(left) || isVarAnchor(right))
			return true;
		if (left.type == AnchorKeyType.FEDINIT_SIGNATURE && right.type == AnchorKeyType.FEDINIT_SIGNATURE) {
			String leftSig = (left.value instanceof String) ? (String) left.value : null;
			String rightSig = (right.value instanceof String) ? (String) right.value : null;
			String leftBase = stripTrailingFTypeSuffix(leftSig);
			String rightBase = stripTrailingFTypeSuffix(rightSig);
			if (leftBase != null && rightBase != null && leftBase.equals(rightBase))
				return true;
			FType leftType = getFTypeFromAnchorKey(leftSig);
			FType rightType = getFTypeFromAnchorKey(rightSig);
			if (leftType == null || rightType == null)
				return true;
			if (leftType == rightType)
				return true;
		}
		return false;
	}

	private static boolean isVarAnchor(AnchorKey key) {
		if (key == null || key.type != AnchorKeyType.FEDINIT_SIGNATURE)
			return false;
		if (!(key.value instanceof String))
			return false;
		return ((String) key.value).startsWith("VAR:");
	}

	private static boolean isVarAnchorKey(String anchorKey) {
		return anchorKey != null && anchorKey.startsWith("VAR:");
	}

	private static boolean isNonVarAnchorKey(String anchorKey) {
		return anchorKey != null && !isVarAnchorKey(anchorKey);
	}

		private static void validateAnchorTypeSupported(Hop hop, Hop anchorHop, java.util.Map<Long, FType> fTypeMap) {
			if (anchorHop == null)
				return;
			FType fType = getKnownFType(anchorHop, fTypeMap);
			if (fType == FType.PART || fType == FType.OTHER)
				throw new DMLRuntimeException("CP->FOUT refed anchor has unsupported FType " + fType
						+ " for hop " + hop.getHopID());
		}

		private static Long getAnchorAxisLenIfKnown(Hop anchorHop, FType anchorType) {
			if (anchorHop == null || anchorType == null)
				return null;
			long[] dims = getAnchorDimsIfKnown(anchorHop);
			if (dims != null && dims.length >= 2) {
				return (anchorType == FType.ROW) ? dims[0] : dims[1];
			}

			String signature = null;
			if (anchorHop instanceof DataOp && ((DataOp) anchorHop).getOp() == OpOpData.TRANSIENTREAD) {
				signature = FederatedPlannerUtils.getFedAnchorKey(((DataOp) anchorHop).getName());
			}
			if (signature == null)
				signature = findFedInitSignature(anchorHop);
			if (signature == null)
				return null;
			return parseAxisLenFromSignature(signature);
		}

		private static Long parseAxisLenFromSignature(String signature) {
			if (signature == null)
				return null;
			String sig = stripTrailingFTypeSuffix(signature);
			int sep = sig.indexOf('|');
			if (sep < 0 || sep == sig.length() - 1)
				return null;
			String rangesPart = sig.substring(sep + 1);
			if (rangesPart.isEmpty())
				return null;
			long max = -1;
			for (String token : rangesPart.split(";")) {
				if (token == null || token.isEmpty())
					continue;
				String[] parts = token.split(",");
				if (parts.length < 2)
					continue;
				try {
					long end = Long.parseLong(parts[1]);
					max = Math.max(max, end);
				}
				catch (NumberFormatException ex) {
					return null;
				}
			}
			return (max >= 0) ? max : null;
		}

		private static boolean hasDimMismatch(Hop hop, Hop anchorHop, java.util.Map<Long, FType> fTypeMap) {
			if (anchorHop == null)
				return false;
			long[] dims = getAnchorDimsIfKnown(anchorHop);
			if (dims != null && hop.dimsKnown()) {
				long rlen = hop.getDim1();
				long clen = hop.getDim2();
				return rlen != dims[0] || clen != dims[1];
			}
			if (hop == null || !hop.dimsKnown())
				return false;
			FType anchorType = getKnownFType(anchorHop, fTypeMap);
			if (anchorType != FType.ROW && anchorType != FType.COL)
				return false;
				Long axisLen = getAnchorAxisLenIfKnown(anchorHop, anchorType);
				if (axisLen == null)
					return false;
				return (anchorType == FType.ROW) ? hop.getDim1() != axisLen : hop.getDim2() != axisLen;
			}

		private static boolean isVectorAxisMismatch(Hop hop, Hop anchorHop, java.util.Map<Long, FType> fTypeMap) {
			FType axis = FederatedPlannerUtils.getVectorAxis(hop);
			if (axis == null || anchorHop == null)
				return false;
		FType anchorType = getKnownFType(anchorHop, fTypeMap);
		if (anchorType == null)
			return false;
		if (anchorType != FType.ROW && anchorType != FType.COL)
			return false;
		// Primary mismatch: different partition axes.
		if (anchorType != axis)
			return true;

		// Even if axes match, for dim-mismatch CP->FOUT materialization we must avoid producing
		// empty/incorrect slices (e.g., column-space vectors for ROW-partitioned anchors).
		long[] anchorDims = getAnchorDimsIfKnown(anchorHop);
		if (anchorDims != null && hop.dimsKnown()) {
			long rlen = hop.getDim1();
			long clen = hop.getDim2();
			long aRows = anchorDims[0];
			long aCols = anchorDims[1];
			if (rlen >= 0 && clen >= 0 && aRows >= 0 && aCols >= 0) {
				if (anchorType == FType.ROW) {
					// ROW slicing is only meaningful for column vectors aligned with the anchor rows.
					if (clen != 1 || rlen != aRows)
						return true;
				}
				else if (anchorType == FType.COL) {
					// COL slicing is only meaningful for row vectors aligned with the anchor cols.
					if (rlen != 1 || clen != aCols)
						return true;
				}
			}
		}
			return false;
		}

			private static boolean isMaterializeAxisMismatch(Hop hop, Hop anchorHop, java.util.Map<Long, FType> fTypeMap) {
				if (hop == null || anchorHop == null)
					return false;
				FType anchorType = getKnownFType(anchorHop, fTypeMap);
				if (anchorType != FType.ROW && anchorType != FType.COL)
					return false;
				Long axisLen = getAnchorAxisLenIfKnown(anchorHop, anchorType);
				if (axisLen == null)
					return false;

				long hopAxisLen = (anchorType == FType.ROW) ? hop.getDim1() : hop.getDim2();
				if (hopAxisLen <= 0)
					return false;
				return hopAxisLen != axisLen;
			}

		private static String toFTypeHint(FType fType) {
			if (fType == FType.ROW)
				return "ROW";
			if (fType == FType.COL)
			return "COL";
		if (fType == FType.BROADCAST)
			return "BROADCAST";
		return "FULL";
	}

	private static AnchorKey buildAnchorKey(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		return buildAnchorKey(hop, fTypeMap, new HashSet<>());
	}

	private static AnchorKey buildAnchorKey(Hop hop, java.util.Map<Long, FType> fTypeMap, Set<Long> visited) {
		if (hop == null)
			return null;
		AnchorKey cached = CPFOUT_ANCHOR_CACHE.get(hop.getHopID());
		if (cached != null)
			return cached;
		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() == OpOpData.TRANSIENTREAD) {
				String anchorKey = FederatedPlannerUtils.getFedAnchorKey(dataOp.getName());
				if (anchorKey != null)
					return new AnchorKey(AnchorKeyType.FEDINIT_SIGNATURE, anchorKey);
				// Only fed-init reads (or TR reads with explicit anchorKeys) can provide a
				// runtime federation map. Do not generate anchor keys for arbitrary local
				// transient reads just because they have inferred FType hints.
				if (FederatedPlannerUtils.isFedInitVar(dataOp.getName())) {
					FType fType = getKnownFType(hop, fTypeMap);
					AnchorKey key = buildAnchorKeyForDataOp(dataOp, fType, fTypeMap);
					if (key != null)
						return key;
				}
			}
			if (dataOp.getOp() == OpOpData.FEDERATED) {
				FType fType = getKnownFType(hop, fTypeMap);
				AnchorKey key = buildAnchorKeyForDataOp(dataOp, fType, fTypeMap);
				if (key != null)
					return key;
			}
		}
		if (!isFederatedInput(hop, fTypeMap))
			return null;
		if (!visited.add(hop.getHopID()))
			return null;
		String sig = findFedInitSignature(hop);
		if (sig != null) {
			FType fType = getKnownFType(hop, fTypeMap);
			if (fType != null)
				sig = sig + "|" + fType.name();
			return new AnchorKey(AnchorKeyType.FEDINIT_SIGNATURE, sig);
		}
		AnchorKey inputKey = null;
		List<Hop> inputs = hop.getInput();
		if (inputs != null) {
			for (Hop in : inputs) {
				if (!isFederatedInput(in, fTypeMap))
					continue;
				AnchorKey key = buildAnchorKey(in, fTypeMap, visited);
				if (key == null)
					return null;
				if (inputKey == null)
					inputKey = key;
				else if (!anchorsCompatible(inputKey, key))
					return null;
			}
		}
		return inputKey;
	}

	private static String findFedInitSignature(Hop hop) {
		if (hop == null)
			return null;
		Set<Hop> visited = new HashSet<>();
		Deque<Hop> queue = new ArrayDeque<>();
		queue.add(hop);
		String signature = null;
		while (!queue.isEmpty()) {
			Hop cur = queue.poll();
			if (cur == null || !visited.add(cur))
				continue;
			if (cur instanceof DataOp) {
				DataOp dataOp = (DataOp) cur;
				if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED) {
					String sig = FederatedPlannerUtils.deriveFedInitSignature(dataOp);
					if (sig == null)
						return null;
					if (signature == null)
						signature = sig;
					else if (!signature.equals(sig))
						return null;
				} else if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
						&& FederatedPlannerUtils.isFedInitVar(dataOp.getName())) {
					String sig = FederatedPlannerUtils.getFedInitSignature(dataOp.getName());
					if (sig == null)
						return null;
					if (signature == null)
						signature = sig;
					else if (!signature.equals(sig))
						return null;
				}
			}
			List<Hop> inputs = cur.getInput();
			if (inputs == null)
				continue;
			for (Hop in : inputs)
				queue.add(in);
		}
		return signature;
	}

	private static FType getKnownFType(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (hop == null)
			return null;
		if (fTypeMap != null) {
			FType mapped = fTypeMap.get(hop.getHopID());
			if (mapped != null)
				return mapped;
		}
		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED)
				return getFedInitFTypeIfKnown(dataOp);
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.isFedInitVar(dataOp.getName()))
				return FederatedPlannerUtils.getFedInitFType(dataOp.getName());
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD) {
				FType anchored = getFTypeFromAnchorKey(FederatedPlannerUtils.getFedAnchorKey(dataOp.getName()));
				if (anchored != null)
					return anchored;
			}
		}
		if (HopRewriteUtils.isTransposeOperation(hop) && hop.getInput() != null && !hop.getInput().isEmpty()) {
			FType inType = getKnownFType(hop.getInput().get(0), fTypeMap);
			if (inType == FType.ROW)
				return FType.COL;
			if (inType == FType.COL)
				return FType.ROW;
			if (inType == FType.FULL || inType == FType.BROADCAST)
				return inType;
			return inType;
		}
		return null;
	}

	private static String getFedInitSignature(DataOp fedInit) {
		return FederatedPlannerUtils.deriveFedInitSignature(fedInit);
	}

	private static long[] getFedInitDimsIfKnown(DataOp fedInit) {
		if (fedInit == null || fedInit.getOp() != org.apache.sysds.common.Types.OpOpData.FEDERATED)
			return null;
		int rangeIx = fedInit.getParameterIndex(DataExpression.FED_RANGES);
		if (rangeIx < 0)
			return null;
		Hop rangeHop = fedInit.getInput(rangeIx);
		if (rangeHop == null)
			return null;
		List<Hop> ranges = rangeHop.getInput();
		if (ranges == null || ranges.isEmpty() || ranges.size() % 2 != 0)
			return null;
		long maxRow = -1;
		long maxCol = -1;
		for (int i = 0; i < ranges.size(); i += 2) {
			Hop end = ranges.get(i + 1);
			Long ru = getLiteralLong(end, 0);
			Long cu = getLiteralLong(end, 1);
			if (ru == null || cu == null)
				return null;
			maxRow = Math.max(maxRow, ru);
			maxCol = Math.max(maxCol, cu);
		}
		if (maxRow < 0 || maxCol < 0)
			return null;
		return new long[] { maxRow, maxCol };
	}

	private static FType getFedInitFTypeIfKnown(DataOp fedInit) {
		if (!hasLiteralFedRanges(fedInit))
			return null;
		return FederatedPlannerUtils.deriveFedInitFType(fedInit);
	}

	private static long[] getAnchorDimsIfKnown(Hop anchorHop) {
		if (anchorHop == null)
			return null;
		if (anchorHop instanceof DataOp
				&& ((DataOp) anchorHop).getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED) {
			return getFedInitDimsIfKnown((DataOp) anchorHop);
		}
		if (anchorHop.dimsKnown())
			return new long[] { anchorHop.getDim1(), anchorHop.getDim2() };
		return null;
	}

	private static String findAnchorLabel(Hop anchorHop) {
		if (anchorHop == null)
			return null;
		Set<Hop> visited = new HashSet<>();
		Deque<Hop> queue = new ArrayDeque<>();
		queue.add(anchorHop);
		while (!queue.isEmpty()) {
			Hop cur = queue.poll();
			if (!visited.add(cur))
				continue;
			if (cur instanceof DataOp) {
				DataOp dataOp = (DataOp) cur;
				OpOpData op = dataOp.getOp();
				if (op == OpOpData.TRANSIENTREAD || op == OpOpData.FEDERATED)
					return dataOp.getName();
			}
			for (Hop in : cur.getInput())
				if (in != null)
					queue.add(in);
		}
		return null;
	}

	private static Long getLiteralLong(Hop listHop, int index) {
		if (listHop == null || listHop.getInput().size() <= index)
			return null;
		Hop h = listHop.getInput(index);
		if (!(h instanceof LiteralOp))
			return null;
		long v = HopRewriteUtils.getIntValueSafe((LiteralOp) h);
		return (v == Long.MAX_VALUE) ? null : v;
	}

	private static boolean hasLiteralFedRanges(DataOp fedInit) {
		if (fedInit == null || fedInit.getOp() != org.apache.sysds.common.Types.OpOpData.FEDERATED)
			return false;
		int rangeIx = fedInit.getParameterIndex(DataExpression.FED_RANGES);
		if (rangeIx < 0)
			return false;
		Hop rangeHop = fedInit.getInput(rangeIx);
		if (rangeHop == null)
			return false;
		List<Hop> ranges = rangeHop.getInput();
		if (ranges == null || ranges.isEmpty() || ranges.size() % 2 != 0)
			return false;
		for (int i = 0; i < ranges.size(); i += 2) {
			Hop beg = ranges.get(i);
			Hop end = ranges.get(i + 1);
			Long rl = getLiteralLong(beg, 0);
			Long cl = getLiteralLong(beg, 1);
			Long ru = getLiteralLong(end, 0);
			Long cu = getLiteralLong(end, 1);
			if (rl == null || cl == null || ru == null || cu == null)
				return false;
		}
		return true;
	}

	private enum AnchorKeyType {
		HOP_ID,
		FEDINIT_SIGNATURE
	}

	public enum InputRequirement {
		REQUIRED,
		OPTIONAL,
		AMBIGUOUS
	}

	private enum ShapeClass {
		VECTOR,
		MATRIX,
		UNKNOWN
	}

	private static final class AnchorKey {
		private final AnchorKeyType type;
		private final Object value;

		private AnchorKey(AnchorKeyType type, Object value) {
			this.type = type;
			this.value = value;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			if (!(o instanceof AnchorKey))
				return false;
			AnchorKey other = (AnchorKey) o;
			return type == other.type && Objects.equals(value, other.value);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, value);
		}
	}

	private static final class ParentAnchor {
		private final AnchorKey key;
		private final Hop anchorHop;
		private final boolean empty;

		private ParentAnchor(AnchorKey key, Hop anchorHop) {
			this(key, anchorHop, false);
		}

		private ParentAnchor(AnchorKey key, Hop anchorHop, boolean empty) {
			this.key = key;
			this.anchorHop = anchorHop;
			this.empty = empty;
		}

		private static ParentAnchor empty() {
			return new ParentAnchor(null, null, true);
		}

		private boolean isEmpty() {
			return empty;
		}
	}

	private static final class AnchorSelection {
		private final AnchorKey key;
		private final Hop anchorHop;

		private AnchorSelection(AnchorKey key, Hop anchorHop) {
			this.key = key;
			this.anchorHop = anchorHop;
		}
	}

	private static final class InputCandidate {
		private final Hop hop;
		private final FType fType;

		private InputCandidate(Hop hop, FType fType) {
			this.hop = hop;
			this.fType = fType;
		}
	}
}

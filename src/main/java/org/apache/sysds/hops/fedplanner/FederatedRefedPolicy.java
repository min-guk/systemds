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
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
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

	private FederatedRefedPolicy() {
	}

	public static void registerFromProgram(DMLProgram prog) {
		registerFromProgram(prog, null);
	}

	public static void registerFromProgram(DMLProgram prog, java.util.Map<Long, FType> fTypeMap) {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		if (prog == null)
			return;
		for (StatementBlock sb : prog.getStatementBlocks())
			registerFromStatementBlock(sb, fTypeMap);
		for (String namespaceKey : prog.getNamespaces().keySet()) {
			for (String fname : prog.getFunctionStatementBlocks(namespaceKey).keySet()) {
				FunctionStatementBlock fsb = prog.getFunctionStatementBlock(namespaceKey, fname);
				registerFromStatementBlock(fsb, fTypeMap);
			}
		}
	}

	public static void registerFromFunction(FunctionStatementBlock function) {
		registerFromFunction(function, null);
	}

	public static void registerFromFunction(FunctionStatementBlock function, java.util.Map<Long, FType> fTypeMap) {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		if (function == null)
			return;
		FunctionStatement fstmt = (FunctionStatement) function.getStatement(0);
		if (fstmt == null)
			return;
		for (StatementBlock inner : fstmt.getBody())
			registerFromStatementBlock(inner, fTypeMap);
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
		if (clearRegistry)
			FederatedRefedRegistry.clear();
		if (clearRegistry)
			FederatedFoutMaterializeRegistry.clear();
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

		AnchorSelection blockAnchor = buildBlockAnchorSelection(all, fTypeMap, runtimeSignatures);
		if (blockAnchor == null) {
			AnchorSelection synthetic = buildSyntheticAnchorSelection(all, fTypeMap, runtimeSignatures, runtimeTypes);
			if (synthetic != null) {
				all.add(synthetic.anchorHop);
				roots.add(synthetic.anchorHop);
				blockAnchor = synthetic;
			}
		}
		for (Hop hop : all) {
			ExecType exec = getPlannedExecType(hop);
			if (exec == null)
				exec = ExecType.CP;
			if (exec == ExecType.CP && hop.getFederatedOutput() == FederatedOutput.FOUT) {
				try {
					validateAndRegister(hop, fTypeMap, sbId, blockAnchor);
				}
				catch (RuntimeException ex) {
					LOG.error("CP->FOUT refed candidate failed: hopID=" + (hop != null ? hop.getHopID() : -1)
						+ " ident=" + (hop != null ? System.identityHashCode(hop) : -1)
						+ " name=" + (hop != null ? hop.getName() : "null")
						+ " op=" + (hop != null ? hop.getOpString() : "null")
						+ " dataType=" + (hop != null ? hop.getDataType() : null)
						+ " forcedExecType=" + (hop != null ? hop.getForcedExecType() : null)
						+ " execType=" + (hop != null ? hop.getExecType() : null)
						+ " federatedOutput=" + (hop != null ? hop.getFederatedOutput() : null)
						+ " sbId=" + sbId, ex);
					throw ex;
				}
			}
		}
	}

	private static void registerFromStatementBlock(StatementBlock sb, java.util.Map<Long, FType> fTypeMap) {
		if (sb == null)
			return;
		Set<Hop> roots = new HashSet<>();
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			if (isb.getPredicateHops() != null)
				roots.add(isb.getPredicateHops());
			if (!roots.isEmpty())
				registerFromHops(new ArrayList<>(roots), false, fTypeMap, sb.getSBID());
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			for (StatementBlock inner : istmt.getIfBody())
				registerFromStatementBlock(inner, fTypeMap);
			for (StatementBlock inner : istmt.getElseBody())
				registerFromStatementBlock(inner, fTypeMap);
		} else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			if (fsb.getFromHops() != null)
				roots.add(fsb.getFromHops());
			if (fsb.getToHops() != null)
				roots.add(fsb.getToHops());
			if (fsb.getIncrementHops() != null)
				roots.add(fsb.getIncrementHops());
			if (!roots.isEmpty())
				registerFromHops(new ArrayList<>(roots), false, fTypeMap, sb.getSBID());
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				registerFromStatementBlock(inner, fTypeMap);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			if (wsb.getPredicateHops() != null)
				roots.add(wsb.getPredicateHops());
			if (!roots.isEmpty())
				registerFromHops(new ArrayList<>(roots), false, fTypeMap, sb.getSBID());
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			for (StatementBlock inner : wstmt.getBody())
				registerFromStatementBlock(inner, fTypeMap);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				registerFromStatementBlock(inner, fTypeMap);
		} else {
			if (sb.getHops() != null)
				roots.addAll(sb.getHops());
			if (!roots.isEmpty())
				registerFromHops(new ArrayList<>(roots), false, fTypeMap, sb.getSBID());
		}
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
		AnchorSelection selection = selectAnchor(hop, fTypeMap, false, false, null);
		if (selection == null)
			return false;
		try {
			validateAnchorTypeSupported(hop, selection.anchorHop, fTypeMap);
		} catch (DMLRuntimeException ex) {
			return false;
		}
		return true;
	}

	private static void validateAndRegister(Hop hop, java.util.Map<Long, FType> fTypeMap, long sbId,
			AnchorSelection blockAnchor) {
		if (!hop.getDataType().isMatrix())
			throw new DMLRuntimeException("CP->FOUT refed supports only matrix outputs for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		if (hop.getDataType().isFrame())
			throw new DMLRuntimeException("CP->FOUT refed does not support frame outputs for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");

		AnchorSelection selection = selectAnchor(hop, fTypeMap, true, true, blockAnchor);
		if (selection == null) {
			System.out.printf("CP->FOUT decision: LOUT (no_single_anchor) hopID=%d op=%s%n",
				hop.getHopID(), hop.getOpString());
			hop.setFederatedOutput(FederatedOutput.LOUT);
			return;
		}
		validateAnchorTypeSupported(hop, selection.anchorHop, fTypeMap);
		if (hasDimMismatch(hop, selection.anchorHop, fTypeMap)) {
			long scopeId = (sbId >= 0) ? sbId : DEFAULT_SBID;
			String fTypeHint = toFTypeHint(getKnownFType(selection.anchorHop, fTypeMap));
			long[] anchorDims = getAnchorDimsIfKnown(selection.anchorHop);
			String anchorDimStr = (anchorDims != null)
				? "(" + anchorDims[0] + "," + anchorDims[1] + ")"
				: "(unknown)";
			String anchorLabel = findAnchorLabel(selection.anchorHop);
			System.out.printf("CP->FOUT decision: MATERIALIZE (dim_mismatch) hopID=%d op=%s local=(%d,%d) anchor=%d anchorDims=%s fTypeHint=%s anchorLabel=%s%n",
				hop.getHopID(), hop.getOpString(),
				hop.getDim1(), hop.getDim2(),
				selection.anchorHop.getHopID(), anchorDimStr, fTypeHint, anchorLabel);
			FederatedFoutMaterializeRegistry.register(scopeId, hop.getHopID(),
				selection.anchorHop.getHopID(), fTypeHint, anchorLabel);
			return;
		}
		long scopeId = (sbId >= 0) ? sbId : DEFAULT_SBID;
		System.out.printf("CP->FOUT decision: REFED hopID=%d op=%s anchor=%d%n",
			hop.getHopID(), hop.getOpString(), selection.anchorHop.getHopID());
		FederatedRefedRegistry.register(scopeId, hop.getHopID(), selection.anchorHop.getHopID());
	}

	private static AnchorSelection selectAnchor(Hop hop, java.util.Map<Long, FType> fTypeMap,
				boolean onlyFedParents, boolean throwOnFailure, AnchorSelection blockAnchor) {
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
			if (!hasFederatedInput(parent, hop, fTypeMap)) {
				if (debug != null)
					debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString()
						+ "): no federated inputs besides target");
				continue;
			}

			ParentAnchor parentAnchor;
			try {
				parentAnchor = determineParentAnchor(parent, hop, fTypeMap, throwOnFailure, true);
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
			if (parentAnchor == null)
				return null;
			if (parentAnchor.isEmpty()) {
				if (debug != null)
					debug.add("skip parent=" + parent.getHopID() + " (" + parent.getOpString() + "): empty anchor");
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
			} else if (!selectedKey.equals(parentAnchor.key)) {
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
			if (blockAnchor != null) {
				if (debug != null)
					debug.add("fallback blockAnchor=" + blockAnchor.anchorHop.getHopID()
						+ " anchorKey=" + blockAnchor.key);
				return blockAnchor;
			}
			if (throwOnFailure) {
				if (debug != null)
					LOG.error("CP->FOUT refed missing FED anchor-parent for hop " + hop.getHopID()
						+ " (" + hop.getOpString() + "): " + String.join(" | ", debug));
				throw new DMLRuntimeException("CP->FOUT refed requires at least one FED parent/worker pool for hop "
							+ hop.getHopID() + " (" + hop.getOpString() + ")"
							+ " [no federated parent/worker pool]");
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

	private static AnchorSelection buildBlockAnchorSelection(List<Hop> hops, java.util.Map<Long, FType> fTypeMap,
			java.util.Map<String, String> runtimeSignatures) {
		if (hops == null || hops.isEmpty())
			return null;
		AnchorKey selectedKey = null;
		Hop selectedAnchor = null;
		for (Hop hop : hops) {
			String runtimeSig = getRuntimeSignature(hop, runtimeSignatures);
			if (runtimeSig == null) {
				if (!isBlockAnchorCandidate(hop))
					continue;
				if (findFedInitSignature(hop) == null)
					continue;
			}
			FType fType = getKnownFType(hop, fTypeMap);
			AnchorKey key = (runtimeSig != null)
				? buildAnchorKeyFromSignature(runtimeSig, fType)
				: buildAnchorKey(hop, fTypeMap);
			if (key == null)
				continue;
			if (selectedKey == null) {
				selectedKey = key;
				selectedAnchor = hop;
			} else if (!selectedKey.equals(key)) {
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
		if (signature == null)
			return null;
		String anchorVar = selectVarForSignature(runtimeSignatures, runtimeTypes, preferredType, signature);
		if (signature == null || anchorVar == null || anchorVar.isEmpty())
			return null;

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
		if (base == InputRequirement.AMBIGUOUS)
			base = resolveVectorVectorRequirement(parent, target, targetIndex, fTypeMap);
		if (base != InputRequirement.REQUIRED)
			return base;
		if (target != null && target.getDataType().isMatrix()) {
			FType targetFType = getKnownFType(target, fTypeMap);
			if (targetFType == FType.PART || targetFType == FType.OTHER)
				return InputRequirement.AMBIGUOUS;
			if (targetFType == FType.BROADCAST)
				return InputRequirement.OPTIONAL;
			if (targetFType == FType.FULL && isBroadcastableFullInput(parent, target, fTypeMap))
				return InputRequirement.OPTIONAL;
		}
		if ((parent instanceof TernaryOp || parent instanceof QuaternaryOp
				|| parent instanceof ParameterizedBuiltinOp)
				&& hasFederatedInput(parent, target, fTypeMap)) {
			return InputRequirement.AMBIGUOUS;
		}
		return base;
	}

	private static ParentAnchor determineParentAnchor(Hop parent, Hop target,
			java.util.Map<Long, FType> fTypeMap, boolean throwOnFailure, boolean allowEmpty) {
		List<InputCandidate> candidates = new ArrayList<>();
		boolean hasPartitioned = false;
		List<Hop> inputs = parent.getInput();
		for (int i = 0; i < inputs.size(); i++) {
			Hop input = inputs.get(i);
			if (input == null || input == target)
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
			FType fType = getKnownFType(input, fTypeMap);
			if (fType == null) {
				if (throwOnFailure)
					throw new DMLRuntimeException("CP->FOUT refed cannot resolve FType for hop "
							+ target.getHopID() + " under parent " + parent.getHopID());
				return null;
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
				if (throwOnFailure)
					throw new DMLRuntimeException("CP->FOUT refed requires federated input anchor for hop "
							+ target.getHopID() + " under parent " + parent.getHopID());
				return null;
			}
			if (parentKey == null) {
				parentKey = key;
				parentAnchor = cand.hop;
			} else if (!parentKey.equals(key)) {
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

	private static InputRequirement classifyInput(Hop parent, Hop input, int index,
			java.util.Map<Long, FType> fTypeMap) {
		if (input == null)
			return InputRequirement.OPTIONAL;
		if (!input.getDataType().isMatrix())
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
		if (fType == null)
			return InputRequirement.AMBIGUOUS;
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
			return index == 0 ? InputRequirement.REQUIRED : InputRequirement.AMBIGUOUS;
		if (parent instanceof QuaternaryOp)
			return index == 0 ? InputRequirement.REQUIRED : InputRequirement.AMBIGUOUS;
		if (parent instanceof TernaryOp)
			return index == 0 ? InputRequirement.REQUIRED : InputRequirement.AMBIGUOUS;
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
		return InputRequirement.AMBIGUOUS;
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
		if (hop == null)
			return false;
		if (hop.hasFederatedOutput())
			return true;
		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED)
				return true;
			if (dataOp.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.isFedInitVar(dataOp.getName()))
				return true;
		}
		return false;
	}

	private static boolean isFederatedInput(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (isFederatedInput(hop))
			return true;
		if (hop == null || fTypeMap == null)
			return false;
		return fTypeMap.get(hop.getHopID()) != null;
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

	private static void validateAnchorTypeSupported(Hop hop, Hop anchorHop, java.util.Map<Long, FType> fTypeMap) {
		if (anchorHop == null)
			return;
		FType fType = getKnownFType(anchorHop, fTypeMap);
		if (fType == FType.PART || fType == FType.OTHER)
			throw new DMLRuntimeException("CP->FOUT refed anchor has unsupported FType " + fType
					+ " for hop " + hop.getHopID());
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
		return false;
	}

	private static String toFTypeHint(FType fType) {
		if (fType == FType.ROW)
			return "ROW";
		if (fType == FType.COL)
			return "COL";
		return "FULL";
	}

	private static AnchorKey buildAnchorKey(Hop hop, java.util.Map<Long, FType> fTypeMap) {
		if (hop == null)
			return null;
		if (!isFederatedInput(hop, fTypeMap))
			return null;
		String sig = findFedInitSignature(hop);
		if (sig != null) {
			FType fType = getKnownFType(hop, fTypeMap);
			if (fType != null)
				sig = sig + "|" + fType.name();
			return new AnchorKey(AnchorKeyType.FEDINIT_SIGNATURE, sig);
		}
		return new AnchorKey(AnchorKeyType.HOP_ID, hop.getHopID());
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

	private enum InputRequirement {
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

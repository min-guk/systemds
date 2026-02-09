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

package org.apache.sysds.lops.compile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.Federated;
import org.apache.sysds.lops.FederatedFoutMaterialize;
import org.apache.sysds.lops.FederatedRefed;
import org.apache.sysds.lops.FunctionCallCP;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.Lop.Type;
import org.apache.sysds.lops.LopsException;
import org.apache.sysds.lops.OutputParameters;
import org.apache.sysds.lops.UnaryCP;
import org.apache.sysds.lops.compile.linearization.IDagLinearizer;
import org.apache.sysds.lops.compile.linearization.IDagLinearizerFactory;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.parfor.util.IDSequence;
import org.apache.sysds.runtime.instructions.CPInstructionParser;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.Instruction.IType;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.SPInstructionParser;
import org.apache.sysds.runtime.instructions.cp.CPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPInstruction.CPType;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;

/**
 * 
 * Class to maintain a DAG of lops and compile it into 
 * runtime instructions, incl piggybacking into jobs.
 * 
 * @param <N> the class parameter has no affect and is
 * only kept for documentation purposes.
 */
public class Dag<N extends Lop>
{
	private static final Log LOG = LogFactory.getLog(Dag.class.getName());

	private static IDSequence job_id = null;
	private static IDSequence var_index = null;
	private static final boolean LOG_LOP_MAPPING = true;
	
	private String scratch = "";
	private String scratchFilePath = null;

	// list of all nodes in the dag
	private ArrayList<Lop> nodes = null;

	static {
		job_id = new IDSequence();
		var_index = new IDSequence();
	}

	private static class NodeOutput {
		FileFormat outInfo;
		ArrayList<Instruction> preInstructions; // instructions added before a MR instruction
		ArrayList<Instruction> lastInstructions;
		
		NodeOutput() {
			outInfo = null;
			preInstructions = new ArrayList<>(); 
			lastInstructions = new ArrayList<>();
		}
		
		public FileFormat getOutInfo() {
			return outInfo;
		}
		public void setOutInfo(FileFormat outInfo) {
			this.outInfo = outInfo;
		}
		public ArrayList<Instruction> getPreInstructions() {
			return preInstructions;
		}
		public void addPreInstruction(Instruction inst) {
			preInstructions.add(inst);
		}
		public ArrayList<Instruction> getLastInstructions() {
			return lastInstructions;
		}
		public void addLastInstruction(Instruction inst) {
			lastInstructions.add(inst);
		}
	}
	
	public Dag() {
		//allocate internal data structures
		nodes = new ArrayList<>();
	}
	
	///////
	// filename handling
	
	private String getFilePath() {
		if ( scratchFilePath == null ) {
			scratchFilePath = scratch + Lop.FILE_SEPARATOR
				+ Lop.PROCESS_PREFIX + DMLScript.getUUID()
				+ Lop.FILE_SEPARATOR + Lop.FILE_SEPARATOR
				+ Lop.CP_ROOT_THREAD_ID + Lop.FILE_SEPARATOR;
		}
		return scratchFilePath;
	}

	public static String getNextUniqueFilenameSuffix() {
		return "temp" + job_id.getNextID();
	}
	
	public String getNextUniqueFilename() {
		return getFilePath() + getNextUniqueFilenameSuffix();
	}
	
	public static String getNextUniqueVarname(DataType dt) {
		return (dt.isMatrix() ? Lop.MATRIX_VAR_NAME_PREFIX :
			dt.isFrame() ? Lop.FRAME_VAR_NAME_PREFIX :
			Lop.SCALAR_VAR_NAME_PREFIX) + var_index.getNextID();
	}
	
	///////
	// Dag modifications
	
	/**
	 * Method to add a node to the DAG.
	 * 
	 * @param node low-level operator
	 * @return true if node was not already present, false if not.
	 */
	public boolean addNode(Lop node) {
		if (nodes.contains(node))
			return false;
		nodes.add(node);
		return true;
	}

	/**
	 * Method to compile a dag generically
	 * 
	 * @param sb statement block
	 * @param config dml configuration
	 * @return list of instructions
	 */
	public ArrayList<Instruction> getJobs(StatementBlock sb, DMLConfig config) {
		if (config != null) {
			scratch = config.getTextValue(DMLConfig.SCRATCH_SPACE) + "/";
		}

		IDagLinearizer dl = IDagLinearizerFactory.createDagLinearizer();
		List<Lop> node_v = dl.linearize(nodes);
		boolean modified = prefetchFederated(node_v);
		modified |= insertRefedLops(node_v, sb);
		modified |= insertFoutMaterializeLops(node_v, sb);

		// The default linearizer for CP/Spark relies on lop IDs (creation order) to satisfy dependencies.
		// Lops inserted in these post-processing passes are created after their consumers, so we must
		// (re-)linearize using true DAG dependencies before generating instructions (otherwise we can
		// emit instructions with "null" operands due to missing labels on not-yet-processed inputs).
		node_v = linearizeTopological(modified ? dl.linearize(nodes) : node_v);

		modified |= insertImplicitRefedForFedOps(node_v);
		if (modified)
			node_v = linearizeTopological(dl.linearize(nodes));

		// do greedy grouping of operations
		ArrayList<Instruction> inst = doPlainInstructionGen(sb, node_v);

		// cleanup instruction (e.g., create packed rmvar instructions)
		return cleanupInstructions(inst);
	}

	/**
	 * Safety net: FED lops require federated matrix inputs with a federation map. If the planner leaves
	 * any matrix inputs as local, execution will either fail (no anchor) or implicitly rely on runtime
	 * fallbacks for local matrix inputs (which can break alignment assumptions). We fix this by inserting
	 * {@link FederatedRefed} lops that upload local matrix inputs to the federated sites, anchored on a
	 * stable federated variable when possible.
	 */
	private boolean insertImplicitRefedForFedOps(List<Lop> lops) {
		if (lops == null || lops.isEmpty())
			return false;

		boolean inserted = false;
		for (int i = 0; i < lops.size(); i++) {
			Lop lop = lops.get(i);
			if (lop == null || lop.getExecType() != ExecType.FED)
				continue;
			// FED init/materialize/refed ops are self-contained or already provide a mapping.
			if (lop instanceof Federated || lop instanceof FederatedRefed || lop instanceof FederatedFoutMaterialize)
				continue;

			// Prefer an existing federated input of this lop as anchor; otherwise fall back to a prior
			// federated matrix in the DAG (legacy behavior for missing-anchor situations).
			Lop anchor = findFederatedAnchorInInputs(lop, null);
			if (anchor == null) {
				Lop localMatrixInput = findLocalMatrixInput(lop);
				if (localMatrixInput == null)
					continue;
				anchor = findFederatedAnchorBefore(lops, i, localMatrixInput);
				if (anchor == null)
					continue;

				FederatedRefed refed = new FederatedRefed(localMatrixInput, anchor,
					localMatrixInput.getDataType(), localMatrixInput.getValueType());
				refed.getOutputParameters().setLabel(getNextUniqueVarname(refed.getDataType()));
				copyOutputParams(refed.getOutputParameters(), localMatrixInput.getOutputParameters());
				refed.setFederatedOutput(FederatedOutput.FOUT);
				addNode(refed);

				lop.replaceInput(localMatrixInput, refed);
				localMatrixInput.removeOutput(lop);
				refed.addOutput(lop);

				if (!lops.contains(refed))
					lops.add(i, refed);
				inserted = true;
				i++; // skip newly inserted refed
				continue;
			}

			// Anchor exists: upload any remaining local matrix inputs (except scalar-like 1x1 matrices)
			// so FED execution does not depend on runtime fallbacks for local matrices.
			List<Lop> inputs = new ArrayList<>(lop.getInputs());
			for (Lop input : inputs) {
				if (input == null || input == anchor)
					continue;
				if (input.getDataType() == null || !input.getDataType().isMatrix())
					continue;
				if (isFederatedMatrixLop(input))
					continue;
				if (isScalarLikeMatrixLop(input))
					continue;

				FederatedRefed refed = new FederatedRefed(input, anchor, input.getDataType(), input.getValueType());
				refed.getOutputParameters().setLabel(getNextUniqueVarname(refed.getDataType()));
				copyOutputParams(refed.getOutputParameters(), input.getOutputParameters());
				refed.setFederatedOutput(FederatedOutput.FOUT);
				addNode(refed);

				lop.replaceInput(input, refed);
				input.removeOutput(lop);
				refed.addOutput(lop);

				if (!lops.contains(refed))
					lops.add(i, refed);
				inserted = true;
				i++; // skip newly inserted refed
			}
		}

		return inserted;
	}

	private static boolean hasFederatedMatrixInput(Lop lop) {
		if (lop == null)
			return false;
		List<Lop> inputs = lop.getInputs();
		if (inputs == null)
			return false;
		for (Lop input : inputs) {
			if (isFederatedMatrixLop(input))
				return true;
		}
		return false;
	}

	private static Lop findLocalMatrixInput(Lop lop) {
		List<Lop> inputs = (lop != null) ? lop.getInputs() : null;
		if (inputs == null)
			return null;
		for (Lop input : inputs) {
			if (input == null || !input.getDataType().isMatrix())
				continue;
			if (!isFederatedMatrixLop(input))
				return input;
		}
		return null;
	}

	private static Lop findFederatedAnchorBefore(List<Lop> lops, int endExclusive, Lop exclude) {
		Lop fallback = null;
		for (int j = endExclusive - 1; j >= 0; j--) {
			Lop cand = lops.get(j);
			if (cand == null || cand == exclude)
				continue;
			if (!isFederatedMatrixLop(cand))
				continue;
			// Prefer stable anchors that represent actual federated variables (fed-init/TR/FEDERATED Data lops)
			// over intermediate broadcast/materialize outputs which can lead to unintended BROADCAST anchoring.
			if (cand instanceof Data) {
				OpOpData op = ((Data) cand).getOperationType();
				if (op == OpOpData.TRANSIENTREAD || op == OpOpData.FEDERATED)
					return cand;
			}
			if (fallback == null)
				fallback = cand;
		}
		return fallback;
	}

	private static Lop findFederatedAnchorInInputs(Lop lop, Lop exclude) {
		if (lop == null)
			return null;
		List<Lop> inputs = lop.getInputs();
		if (inputs == null || inputs.isEmpty())
			return null;

		Lop fallback = null;
		for (Lop in : inputs) {
			if (in == null || in == exclude)
				continue;
			if (!isFederatedMatrixLop(in))
				continue;
			if (in instanceof Data) {
				OpOpData op = ((Data) in).getOperationType();
				if (op == OpOpData.TRANSIENTREAD || op == OpOpData.FEDERATED)
					return in;
			}
			if (fallback == null)
				fallback = in;
		}
		return fallback;
	}

	private static boolean isScalarLikeMatrixLop(Lop lop) {
		if (lop == null || lop.getDataType() == null || !lop.getDataType().isMatrix())
			return false;
		OutputParameters out = lop.getOutputParameters();
		if (out == null)
			return false;
		return out.getNumRows() == 1 && out.getNumCols() == 1;
	}

	private static boolean isFederatedMatrixLop(Lop lop) {
		if (lop == null || lop.getDataType() == null || !lop.getDataType().isMatrix())
			return false;
		// explicit federated lops always provide a federation map
		if (lop instanceof Federated || lop instanceof FederatedRefed || lop instanceof FederatedFoutMaterialize)
			return true;
		FederatedOutput fedOut = lop.getFederatedOutput();
		if (lop.getExecType() == ExecType.FED)
			return fedOut == null || !fedOut.isForcedLocal();
		// data lops may represent already-federated variables; honor explicit FED exec
		// even if federatedOutput is not forced yet.
		if (lop instanceof Data) {
			if (lop.getExecType() == ExecType.FED)
				return fedOut == null || !fedOut.isForcedLocal();
			OpOpData op = ((Data) lop).getOperationType();
			if (op == OpOpData.TRANSIENTREAD) {
				OutputParameters out = lop.getOutputParameters();
				String name = (out != null) ? out.getLabel() : null;
				if (name != null && FederatedPlannerUtils.getFedAnchorKey(name) != null)
					return true;
			}
			return fedOut != null && fedOut.isForcedFederated();
		}
		return false;
	}

	/**
	 * Checks if the given input needs to be prefetched before executing given lop.
	 * @param input to check for prefetch
	 * @param lop which possibly needs the input prefetched
	 * @return true if given input needs to be prefetched before lop
	 */
	private boolean inputNeedsPrefetch(Lop input, Lop lop){
		return input.prefetchActivated() && lop.getExecType() != ExecType.FED
			&& input.getFederatedOutput().isForcedFederated();
	}

	/**
	 * Add prefetch lop between input and lop.
	 * @param input to be prefetched
	 * @param lop for which the given input needs to be prefetched
	 */
	private void addFedPrefetchLop(Lop input, Lop lop){
		UnaryCP prefetch = new UnaryCP(input, OpOp1.PREFETCH, input.getDataType(), input.getValueType(), ExecType.CP);
		prefetch.addOutput(lop);
		lop.replaceInput(input, prefetch);
		input.removeOutput(lop);
		addNode(prefetch);
	}

	/**
	 * Add prefetch lops where needed.
	 * @param lops for which prefetch lops could be added.
	 */
	private boolean prefetchFederated(List<Lop> lops){
		boolean inserted = false;
		for ( Lop lop : lops ){
			for ( Lop input : lop.getInputs() ){
				if ( inputNeedsPrefetch(input, lop) ) {
					addFedPrefetchLop(input, lop);
					inserted = true;
				}
			}
		}
		return inserted;
	}

	private static List<Lop> linearizeTopological(List<Lop> lops) {
		if (lops == null || lops.size() <= 1)
			return lops;

		// Use the current linearization as a stable tie-breaker to keep scheduling changes minimal.
		final HashMap<Lop, Integer> order = new HashMap<>(lops.size());
		for (int i = 0; i < lops.size(); i++)
			order.put(lops.get(i), i);

		final HashMap<Lop, Integer> indegree = new HashMap<>(lops.size());
		final HashMap<Lop, ArrayList<Lop>> adj = new HashMap<>(lops.size());
		final HashMap<String, Lop> labelToLop = new HashMap<>(lops.size());
		for (Lop lop : lops) {
			indegree.put(lop, 0);
			adj.put(lop, new ArrayList<>());
			OutputParameters out = lop.getOutputParameters();
			if (out != null && out.getLabel() != null) {
				Lop existing = labelToLop.get(out.getLabel());
				if (existing == null || lop instanceof FederatedFoutMaterialize)
					labelToLop.put(out.getLabel(), lop);
			}
		}
		for (Lop lop : lops) {
			for (Lop in : lop.getInputs()) {
				if (in == null)
					continue;
				Lop dep = in;
				OutputParameters inOut = in.getOutputParameters();
				String label = (inOut != null) ? inOut.getLabel() : null;
				if (label != null) {
					Lop labelLop = labelToLop.get(label);
					if (labelLop instanceof FederatedFoutMaterialize)
						dep = labelLop;
				}
				if (!indegree.containsKey(dep) && label != null)
					dep = labelToLop.get(label);
				if (dep == null || !indegree.containsKey(dep))
					continue;
				adj.get(dep).add(lop);
				indegree.put(lop, indegree.get(lop) + 1);
			}
		}

		PriorityQueue<Lop> q = new PriorityQueue<>((a, b) -> Integer.compare(order.get(a), order.get(b)));
		for (Map.Entry<Lop, Integer> e : indegree.entrySet()) {
			if (e.getValue() == 0)
				q.add(e.getKey());
		}

		ArrayList<Lop> out = new ArrayList<>(lops.size());
		while (!q.isEmpty()) {
			Lop cur = q.poll();
			out.add(cur);
			for (Lop succ : adj.get(cur)) {
				int deg = indegree.get(succ) - 1;
				indegree.put(succ, deg);
				if (deg == 0)
					q.add(succ);
			}
		}

		if (out.size() != lops.size()) {
			// Preserve legacy behavior if we detect cycles or missing nodes due to inconsistent edges.
			LOG.warn("Failed to topologically linearize lop DAG (cycle or inconsistent edges); falling back to original order.");
			return lops;
		}
		return out;
	}

	private boolean insertRefedLops(List<Lop> lops, StatementBlock sb) {
		if (FederatedRefedRegistry.isEmpty())
			return false;

		long sbId = (sb != null) ? sb.getSBID() : -1;
		Map<Long, FederatedRefedRegistry.AnchorSpec> refedEntries = FederatedRefedRegistry.snapshot(sbId);
		if (refedEntries.isEmpty())
			return false;
		Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> materializeEntries =
			FederatedFoutMaterializeRegistry.snapshot(sbId);

		Map<Long, Lop> hopToLop = new HashMap<>();
		for (Lop lop : lops) {
			long hopId = lop.getHopID();
			if (hopId < 0)
				continue;
			Lop existing = hopToLop.get(hopId);
			if (existing == null || lop.getLevel() > existing.getLevel())
				hopToLop.put(hopId, lop);
		}

		boolean inserted = false;
		for (Map.Entry<Long, FederatedRefedRegistry.AnchorSpec> e : refedEntries.entrySet()) {
			long hopId = e.getKey();
			if (materializeEntries.containsKey(hopId)) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Skipping refed insertion for hop=" + hopId
						+ " because a fed_fout materialize is already registered");
				}
				continue;
			}
			FederatedRefedRegistry.AnchorSpec anchorSpec = e.getValue();
			long anchorHopId = anchorSpec.getAnchorHopId();
			String anchorKey = anchorSpec.getAnchorKey();
			Lop local = hopToLop.get(hopId);
			Lop anchor = hopToLop.get(anchorHopId);
			if (local == null || (anchor == null && anchorKey == null)) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Skipping refed insertion for hop=" + hopId + " anchor=" + anchorHopId
						+ " due to missing lops in current DAG");
				}
				continue;
			}

			// If the "local" lop is already a federated object (e.g., transient read of a federated var),
			// inserting a refed would fail at runtime because fed_refed expects a local input.
			if (isFederatedMatrixLop(local)) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Skipping refed insertion for hop=" + hopId + " anchor=" + anchorHopId
						+ " because input is already federated");
				}
				continue;
			}

			boolean alreadyInserted = local.getOutputs().stream()
				.anyMatch(out -> out instanceof FederatedRefed);
			if (alreadyInserted)
				continue;

			FederatedRefed refed = (anchor != null)
				? new FederatedRefed(local, anchor, local.getDataType(), local.getValueType())
				: new FederatedRefed(local, anchorKey, local.getDataType(), local.getValueType());
			refed.getOutputParameters().setLabel(getNextUniqueVarname(refed.getDataType()));
			copyOutputParams(refed.getOutputParameters(), local.getOutputParameters());
			refed.setFederatedOutput(FederatedOutput.FOUT);
			addNode(refed);
			inserted = true;

			List<Lop> outputs = new ArrayList<>(local.getOutputs());
			int insertPos = -1;
			for (Lop out : outputs) {
				if (out == refed)
					continue;
				if (out.getExecType() == ExecType.FED) {
					out.replaceInput(local, refed);
					local.removeOutput(out);
					refed.addOutput(out);
					int idx = lops.indexOf(out);
					if (idx >= 0 && (insertPos < 0 || idx < insertPos))
						insertPos = idx;
				}
			}
			if (insertPos >= 0 && !lops.contains(refed))
				lops.add(insertPos, refed);
			else if (!lops.contains(refed))
				lops.add(refed);
		}
		return inserted;
	}

		private boolean insertFoutMaterializeLops(List<Lop> lops, StatementBlock sb) {
			if (FederatedFoutMaterializeRegistry.isEmpty())
				return false;

		long sbId = (sb != null) ? sb.getSBID() : -1;
		Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> entries =
			FederatedFoutMaterializeRegistry.snapshot(sbId);
		if (entries.isEmpty())
			return false;

		Map<Long, Lop> hopToLop = new HashMap<>();
		for (Lop lop : lops) {
			long hopId = lop.getHopID();
			if (hopId < 0)
				continue;
			Lop existing = hopToLop.get(hopId);
			if (existing == null || lop.getLevel() > existing.getLevel())
				hopToLop.put(hopId, lop);
		}

			boolean inserted = false;
			for (Map.Entry<Long, FederatedFoutMaterializeRegistry.MaterializeSpec> e : entries.entrySet()) {
				long hopId = e.getKey();
				FederatedFoutMaterializeRegistry.MaterializeSpec spec = e.getValue();
				Lop local = hopToLop.get(hopId);
				long anchorHopId = spec.getAnchorHopId();
				String anchorKey = spec.getAnchorKey();
				Lop anchor = hopToLop.get(anchorHopId);
				// hopToLop is level-based; for anchors we require a lop with a stable output label
				// (prefer TR/FEDERATED Data lops) to avoid emitting "null" anchor operands.
				if (anchor != null) {
					OutputParameters out = anchor.getOutputParameters();
					if (out == null || out.getLabel() == null) {
						Lop resolved = findAnchorByHopId(lops, anchorHopId);
						if (resolved != null)
							anchor = resolved;
					}
				}
			if (anchor == null && spec.getAnchorLabel() != null) {
				Lop anchorByLabel = findAnchorByLabel(lops, spec.getAnchorLabel());
				if (anchorByLabel != null) {
					anchor = anchorByLabel;
					System.out.printf("CP->FOUT anchor fallback: hop=%d anchorHop=%d anchorLabel=%s resolvedHop=%d%n",
						hopId, anchorHopId, spec.getAnchorLabel(), anchorByLabel.getHopID());
				}
			}
			boolean missingAnchor = (anchor == null && anchorKey == null);
			if (local == null || missingAnchor) {
				System.out.printf("CP->FOUT insert skip: hop=%d anchor=%d missingLocal=%s missingAnchor=%s sbId=%d%n",
					hopId, anchorHopId, local == null, missingAnchor, sbId);
				if (missingAnchor) {
					List<Long> hopIds = new ArrayList<>(hopToLop.keySet());
					Collections.sort(hopIds);
					int limit = 200;
					StringBuilder buf = new StringBuilder();
					for (int i = 0; i < hopIds.size() && i < limit; i++) {
						if (i > 0)
							buf.append(',');
						buf.append(hopIds.get(i));
					}
					System.out.printf("CP->FOUT insert context: sbId=%d hopToLopSize=%d hopIds=%s%s%n",
						sbId, hopIds.size(), buf.toString(), hopIds.size() > limit ? ",..." : "");
				}
				if (LOG.isDebugEnabled()) {
					LOG.debug("Skipping fout materialize insertion for hop=" + hopId + " anchor="
						+ spec.getAnchorHopId() + " due to missing lops in current DAG");
				}
					continue;
				}

				// Special handling for transient writes: the variable doesn't exist yet at the time of materialization.
				// We therefore materialize the computed value (twrite input) and wire the fout result into the twrite.
				final boolean isTransientWrite = (local instanceof Data) && ((Data) local).isTransientWrite();
				Lop materializeInput = local;
				if (isTransientWrite) {
					List<Lop> inputs = local.getInputs();
					if (inputs == null || inputs.isEmpty() || inputs.get(0) == null) {
						System.out.printf("CP->FOUT insert skip: hop=%d transientWrite=true missingInput=true sbId=%d%n",
							hopId, sbId);
						continue;
					}
					materializeInput = inputs.get(0);
				}

				// If the input is already federated (e.g., produced by a FED op and written via TWrite),
				// a fed_fout materialize would fail at runtime because it expects a local input.
				if (isFederatedMatrixLop(materializeInput)) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("Skipping fout materialize insertion for hop=" + hopId + " anchor="
							+ spec.getAnchorHopId() + " because input is already federated");
					}
					continue;
				}

				if (anchor != null && materializeInput != null && isReachable(materializeInput, anchor)) {
					Lop fallbackAnchor = findFallbackAnchor(lops, materializeInput);
					if (fallbackAnchor != null) {
						System.out.printf("CP->FOUT anchor cycle: hop=%d anchorHop=%d fallbackHop=%d%n",
							hopId, anchorHopId, fallbackAnchor.getHopID());
						anchor = fallbackAnchor;
						anchorKey = null;
					}
					else {
						System.out.printf("CP->FOUT insert skip: hop=%d anchorCycle=true sbId=%d%n",
							hopId, sbId);
						continue;
					}
				}

				boolean alreadyInserted = isTransientWrite
					? (materializeInput instanceof FederatedRefed || materializeInput instanceof FederatedFoutMaterialize)
					: local.getOutputs().stream().anyMatch(out -> out instanceof FederatedRefed
						|| out instanceof FederatedFoutMaterialize);
				if (alreadyInserted) {
					String localLabel = (local.getOutputParameters() != null)
						? local.getOutputParameters().getLabel()
						: "null";
				System.out.printf("CP->FOUT insert skip: hop=%d local=%s alreadyInserted=true%n",
					hopId, localLabel);
					continue;
				}

				FederatedFoutMaterialize fout = (anchor != null)
					? new FederatedFoutMaterialize(materializeInput, anchor, spec.getFTypeHint())
					: new FederatedFoutMaterialize(materializeInput, anchorKey, spec.getFTypeHint());
				fout.getOutputParameters().setLabel(getNextUniqueVarname(fout.getDataType()));
				copyOutputParams(fout.getOutputParameters(), local.getOutputParameters());
				fout.setFederatedOutput(FederatedOutput.FOUT);
				addNode(fout);
			String localLabel = (local.getOutputParameters() != null)
				? local.getOutputParameters().getLabel()
				: "null";
			String anchorLabel = (anchor != null && anchor.getOutputParameters() != null)
				? anchor.getOutputParameters().getLabel()
				: (anchorKey != null ? anchorKey : "null");
			System.out.printf("CP->FOUT insert: hop=%d local=%s anchor=%d anchorVar=%s out=%s fTypeHint=%s%n",
					hopId, localLabel, anchorHopId, anchorLabel, fout.getOutputParameters().getLabel(),
					spec.getFTypeHint());
				inserted = true;

				if (isTransientWrite) {
					local.replaceInput(materializeInput, fout);
					materializeInput.removeOutput(local);
					fout.addOutput(local);
				}

				// Rewire all FED consumers of the materialized input to consume the federated value.
				//
				// NOTE: We cannot rely solely on local.getOutputs() because certain lops (e.g., indexing /
				// in-place updates) may introduce intermediate lops with the same hop id, which breaks
				// strict object-identity based replacement. Scan the full lop list and match by hop id
				// as a fallback to ensure the planned CP->FOUT is actually used by FED consumers.
				final long materializeHopId = materializeInput.getHopID();
				final String materializeLabel = (materializeInput.getOutputParameters() != null)
					? materializeInput.getOutputParameters().getLabel()
					: null;
				final Set<String> aliasLabels = new HashSet<>();
				if (materializeLabel != null)
					aliasLabels.add(materializeLabel);
				for (Lop candidate : lops) {
					if (!(candidate instanceof Data))
						continue;
					Data data = (Data) candidate;
					if (!data.isTransientWrite())
						continue;
					List<Lop> inputs = data.getInputs();
					if (inputs == null || inputs.isEmpty() || inputs.get(0) == null)
						continue;
					Lop input = inputs.get(0);
					boolean matchesInput = (input == materializeInput);
					if (!matchesInput && materializeHopId >= 0)
						matchesInput = (input.getHopID() == materializeHopId);
					if (!matchesInput && materializeLabel != null) {
						OutputParameters out = input.getOutputParameters();
						String inLabel = (out != null) ? out.getLabel() : null;
						matchesInput = materializeLabel.equals(inLabel);
					}
					if (!matchesInput)
						continue;
					OutputParameters out = data.getOutputParameters();
					String label = (out != null) ? out.getLabel() : null;
					if (label != null)
						aliasLabels.add(label);
				}
				List<Lop> consumers = new ArrayList<>();
				List<Lop> refedToRemove = new ArrayList<>();
				String anchorVarLabel = (anchor != null && anchor.getOutputParameters() != null)
					? anchor.getOutputParameters().getLabel()
					: anchorKey;
				for (Lop out : lops) {
					if (out == null || out == fout)
						continue;
					// Never rewire the producer (or its same-hop-id aliases) to consume its own
					// fed_fout output; this creates a self-cycle like target <- fed_fout(target).
					if (out == materializeInput || (materializeHopId >= 0 && out.getHopID() == materializeHopId))
						continue;
					boolean isFedExec = out.getExecType() == ExecType.FED;
					boolean isFoutTWrite = (out instanceof Data) && ((Data) out).isTransientWrite()
						&& out.getFederatedOutput() == FederatedOutput.FOUT;
					// Do not rewrite writes of the anchor variable itself. Otherwise loops like
					// Y = rexpand(Y, ...) can become cyclic (Y <- fed_fout(rexpand(Y))).
					if (isFoutTWrite && anchorVarLabel != null && out.getOutputParameters() != null
							&& anchorVarLabel.equals(out.getOutputParameters().getLabel()))
						continue;
					if (!isFedExec && !isFoutTWrite)
						continue;
					List<Lop> outInputs = out.getInputs();
					if (outInputs == null || outInputs.isEmpty())
						continue;
					boolean replacedAny = false;
					boolean usesFout = false;
					String foutLabel = (fout.getOutputParameters() != null)
						? fout.getOutputParameters().getLabel()
						: null;
					for (Lop in : new ArrayList<>(outInputs)) {
						if (in == null)
							continue;
						if (in == fout) {
							usesFout = true;
							continue;
						}
						String inLabel = (in.getOutputParameters() != null)
							? in.getOutputParameters().getLabel()
							: null;
						if (foutLabel != null && foutLabel.equals(inLabel)) {
							out.replaceInput(in, fout);
							in.removeOutput(out);
							fout.addOutput(out);
							replacedAny = true;
							usesFout = true;
							continue;
						}
						if (in == materializeInput) {
							out.replaceInput(in, fout);
							in.removeOutput(out);
							fout.addOutput(out);
							replacedAny = true;
							usesFout = true;
							continue;
						}
						boolean sameLabel = (materializeLabel != null && materializeLabel.equals(inLabel));
						boolean aliasLabel = (inLabel != null && aliasLabels.contains(inLabel));
						boolean sameHopId = (materializeHopId >= 0 && in.getHopID() == materializeHopId);
						if (sameLabel || aliasLabel || sameHopId) {
							out.replaceInput(in, fout);
							in.removeOutput(out);
							fout.addOutput(out);
							replacedAny = true;
							usesFout = true;
						}
					}
						if (replacedAny && out instanceof FederatedRefed) {
							List<Lop> refedOutputs = new ArrayList<>(out.getOutputs());
							for (Lop refOut : refedOutputs) {
								if (refOut == null)
									continue;
								refOut.replaceInput(out, fout);
								out.removeOutput(refOut);
								fout.addOutput(refOut);
							}
							refedToRemove.add(out);
							continue;
						}
						if (usesFout)
							consumers.add(out);
					}

				if (!refedToRemove.isEmpty()) {
					for (Lop refed : refedToRemove) {
						List<Lop> refedInputs = new ArrayList<>(refed.getInputs());
						for (Lop in : refedInputs) {
							if (in != null)
								in.removeOutput(refed);
						}
						lops.remove(refed);
						nodes.remove(refed);
					}
				}

			int inputIdx = (materializeInput != null) ? lops.indexOf(materializeInput) : -1;
			if (inputIdx < 0)
				inputIdx = lops.indexOf(local);
			int anchorIdx = lops.indexOf(anchor);
			int insertPos = Math.max(inputIdx, anchorIdx);
			if (insertPos < 0)
				insertPos = lops.size();
			else
				insertPos++;

			if (!lops.contains(fout))
				lops.add(insertPos, fout);

			if (!consumers.isEmpty()) {
				consumers.sort((a, b) -> Integer.compare(lops.indexOf(a), lops.indexOf(b)));
				for (Lop out : consumers)
					lops.remove(out);
				int pos = lops.indexOf(fout) + 1;
				for (Lop out : consumers)
					lops.add(pos++, out);
			}
		}
		return inserted;
	}

	private static void copyOutputParams(OutputParameters target, OutputParameters source) {
		target.setDimensions(source.getNumRows(), source.getNumCols(), source.getBlocksize(),
			source.getNnz(), source.getUpdateType(), source.getCompressedSize());
		target.setFormat(source.getFormat());
	}

	private static Lop findAnchorByLabel(List<Lop> lops, String label) {
		if (label == null)
			return null;
		for (Lop lop : lops) {
			OutputParameters out = lop.getOutputParameters();
			if (out == null)
				continue;
			if (!label.equals(out.getLabel()))
				continue;
			if (lop instanceof Data) {
				OpOpData op = ((Data) lop).getOperationType();
				if (op == OpOpData.TRANSIENTREAD || op == OpOpData.FEDERATED)
					return lop;
			}
		}
		return null;
	}

	private static Lop findAnchorByHopId(List<Lop> lops, long hopId) {
		if (hopId < 0)
			return null;
		Lop fallback = null;
		for (Lop lop : lops) {
			if (lop.getHopID() != hopId)
				continue;
			OutputParameters out = lop.getOutputParameters();
			if (out == null || out.getLabel() == null)
				continue;
			if (lop instanceof Data) {
				OpOpData op = ((Data) lop).getOperationType();
				if (op == OpOpData.TRANSIENTREAD || op == OpOpData.FEDERATED)
					return lop;
			}
			if (fallback == null)
				fallback = lop;
		}
		return fallback;
	}

	private static boolean isReachable(Lop from, Lop target) {
		if (from == null || target == null)
			return false;
		if (from == target)
			return true;
		HashSet<Lop> visited = new HashSet<>();
		ArrayDeque<Lop> stack = new ArrayDeque<>();
		stack.push(from);
		while (!stack.isEmpty()) {
			Lop cur = stack.pop();
			if (!visited.add(cur))
				continue;
			if (cur == target)
				return true;
			List<Lop> outs = cur.getOutputs();
			if (outs == null || outs.isEmpty())
				continue;
			for (Lop out : outs) {
				if (out != null)
					stack.push(out);
			}
		}
		return false;
	}

	private static Lop findFallbackAnchor(List<Lop> lops, Lop materializeInput) {
		if (lops == null || lops.isEmpty())
			return null;
		for (Lop candidate : lops) {
			if (candidate == null || candidate == materializeInput)
				continue;
			if (candidate.getExecType() != ExecType.FED)
				continue;
			if (candidate.getFederatedOutput() != FederatedOutput.FOUT)
				continue;
			OutputParameters out = candidate.getOutputParameters();
			if (out == null || out.getLabel() == null)
				continue;
			if (isReachable(materializeInput, candidate))
				continue;
			return candidate;
		}
		return null;
	}
	
	private ArrayList<Instruction> doPlainInstructionGen(StatementBlock sb, List<Lop> nodes)
	{
		//prepare basic instruction sets
		List<Instruction> deleteInst = new ArrayList<>();
		List<Instruction> writeInst = deleteUpdatedTransientReadVariables(sb, nodes);
		List<Instruction> endOfBlockInst = generateRemoveInstructions(sb, nodes);
		ArrayList<Instruction> inst = generateInstructionsForInputVariables(nodes);
		
		// filter out non-executable nodes
		List<Lop> execNodes = nodes.stream()
			.filter(l -> (!l.isDataExecLocation() 
				|| (((Data)l).getOperationType().isWrite() && !isTransientWriteRead((Data)l))
				|| (((Data)l).isPersistentRead() && l.getDataType().isScalar())))
			.collect(Collectors.toList());
		
		// generate executable instruction
		generateControlProgramJobs(execNodes, inst, writeInst, deleteInst);

		// add write and delete inst at the very end.
		inst.addAll(writeInst);
		inst.addAll(deleteInst);
		inst.addAll(endOfBlockInst);
		return inst;
	}
	
	private static boolean isTransientWriteRead(Data dnode) {
		Lop input = dnode.getInputs().get(0);
		return dnode.getOperationType().isTransient() 
			&& input.isDataExecLocation() && ((Data)input).getOperationType().isTransient() 
			&& dnode.getOutputParameters().getLabel().equals(input.getOutputParameters().getLabel());
	}
	
	private static List<Instruction> deleteUpdatedTransientReadVariables(StatementBlock sb, List<Lop> nodeV) {
		List<Instruction> insts = new ArrayList<>();
		if ( sb == null ) //return modifiable list
			return insts;

		if( LOG.isTraceEnabled() )
			LOG.trace("In delete updated variables");

		Set<String> fedInitLabels = new HashSet<>();
		for (Lop node : nodeV) {
			if (node instanceof Federated) {
				OutputParameters out = node.getOutputParameters();
				if (out != null && out.getLabel() != null)
					fedInitLabels.add(out.getLabel());
			}
		}

		// CANDIDATE list of variables which could have been updated in this statement block 
		HashMap<String, Lop> labelNodeMapping = new HashMap<>(nodeV.size());
		
		// ACTUAL list of variables whose value is updated, AND the old value of the variable 
		// is no longer accessible/used.
		HashSet<String> updatedLabels = new HashSet<>(nodeV.size());
		HashMap<String, Lop> updatedLabelsLineNum =  new HashMap<>(nodeV.size());
		
		// first capture all transient read variables
		for ( Lop node : nodeV ) {
			if (node.isDataExecLocation()
					&& ((Data) node).getOperationType().isTransient()
					&& ((Data) node).getOperationType().isRead()
					&& ((Data) node).getDataType() == DataType.MATRIX) {
				// "node" is considered as updated ONLY IF the old value is not used any more
				// So, make sure that this READ node does not feed into any (transient/persistent) WRITE
				boolean hasWriteParent=false;
				for(Lop p : node.getOutputs()) {
					if(p.isDataExecLocation()) {
						// if the "p" is of type Data, then it has to be a WRITE
						hasWriteParent = true;
						break;
					}
				}
				if ( !hasWriteParent ) {
					// node has no parent of type WRITE, so this is a CANDIDATE variable 
					// add it to labelNodeMapping so that it is considered in further processing
					labelNodeMapping.put(node.getOutputParameters().getLabel(), node);
				}
			}
		}

		// capture updated transient write variables
		for ( Lop node : nodeV ) {
			if (node.isDataExecLocation()
					&& ((Data) node).getOperationType().isTransient()
					&& ((Data) node).getOperationType().isWrite()
					&& ((Data) node).getDataType() == DataType.MATRIX
					&& labelNodeMapping.containsKey(node.getOutputParameters().getLabel()) // check to make sure corresponding (i.e., with the same label/name) transient read is present
					&& !labelNodeMapping.containsValue(node.getInputs().get(0)) ){ // check to avoid cases where transient read feeds into a transient write
				updatedLabels.add(node.getOutputParameters().getLabel());
				updatedLabelsLineNum.put(node.getOutputParameters().getLabel(), node);
			}
		}
		
		Set<String> protectedLabels = collectFedInputLabels(nodeV);
		protectedLabels.addAll(fedInitLabels);

		// generate RM instructions
		for ( String label : updatedLabels ) {
			if (label != null && protectedLabels.contains(label))
				continue;
			if ("Y".equals(label) || "X".equals(label)) {
				System.out.println("[DEBUG] rmvar " + label + " from deleteUpdatedTransientReadVariables");
			}
			Instruction rm_inst = VariableCPInstruction.prepareRemoveInstruction(label);
			rm_inst.setLocation(updatedLabelsLineNum.get(label));
			if( LOG.isTraceEnabled() )
				LOG.trace(rm_inst.toString());
			insts.add(rm_inst);
		}
		return insts;
	}
	
	private static List<Instruction> generateRemoveInstructions(StatementBlock sb, List<Lop> nodeV) {
		if ( sb == null ) 
			return Collections.emptyList();
		ArrayList<Instruction> insts = new ArrayList<>();

		if( LOG.isTraceEnabled() )
			LOG.trace("In generateRemoveInstructions()");

		Set<String> fedInitLabels = new HashSet<>();
		if (nodeV != null) {
			for (Lop node : nodeV) {
				if (node instanceof Federated) {
					OutputParameters out = node.getOutputParameters();
					if (out != null && out.getLabel() != null)
						fedInitLabels.add(out.getLabel());
				}
			}
		}
		Set<String> protectedLabels = collectFedInputLabels(nodeV);
		protectedLabels.addAll(fedInitLabels);
		
		// RULE 1: if in IN and not in OUT, then there should be an rmvar or rmfilevar inst
		// (currently required for specific cases of external functions)
		for (String varName : sb.liveIn().getVariableNames()) {
			if (!sb.liveOut().containsVariable(varName)) {
				if (protectedLabels.contains(varName))
					continue;
				if ("Y".equals(varName) || "X".equals(varName)) {
					System.out.println("[DEBUG] rmvar " + varName + " from generateRemoveInstructions");
				}
				Instruction inst = VariableCPInstruction.prepareRemoveInstruction(varName);
				inst.setLocation(sb.getFilename(), sb.getEndLine(), sb.getEndLine(), -1, -1);
				insts.add(inst);
				if( LOG.isTraceEnabled() )
					LOG.trace("  Adding " + inst.toString());
			}
		}
		return insts;
	}

	/**
	 * Method to generate createvar instructions, which creates a new entry
	 * in the symbol table. One instruction is generated for every LOP that is 
	 * 1) type Data and 
	 * 2) persistent and 
	 * 3) matrix and 
	 * 4) read
	 * 
	 * Transient reads needn't be considered here since the previous program 
	 * block would already create appropriate entries in the symbol table.
	 * 
	 * @param nodes_v list of nodes
	 * @return list of instructions
	 */
	private static ArrayList<Instruction> generateInstructionsForInputVariables(List<Lop> nodes_v) {
		ArrayList<Instruction> insts = new ArrayList<>();
		for(Lop n : nodes_v) {
			if (n.isDataExecLocation() 
				&& !((Data) n).getOperationType().isTransient()
				&& ((Data) n).getOperationType().isRead()
				&& (n.getDataType() == DataType.MATRIX || n.getDataType() == DataType.FRAME 
				   || n.getDataType() == DataType.LIST) )
			{
				if ( !((Data)n).isLiteral() ) {
					try {
						String inst_string = n.getInstructions();
						CPInstruction currInstr = CPInstructionParser.parseSingleInstruction(inst_string);
						currInstr.setLocation(n);
						// TODO find a more direct way of communicating the privacy constraints
						// (visible to runtime explain); This change should apply to all occurrences.
						insts.add(currInstr);
					} catch (DMLRuntimeException e) {
						throw new LopsException(n.printErrorLocation() + "error generating instructions from input variables in Dag -- \n", e);
					}
				}
			}
		}
		return insts;
	}
	
	/**
	 * Exclude rmvar instruction for varname from deleteInst, if exists
	 * 
	 * @param varName variable name
	 * @param deleteInst list of instructions
	 */
	private static void excludeRemoveInstruction(String varName, List<Instruction> deleteInst) {
		for(int i=0; i < deleteInst.size(); i++) {
			Instruction inst = deleteInst.get(i);
			if ((inst.getType() == IType.CONTROL_PROGRAM  || inst.getType() == IType.SPARK)
					&& ((CPInstruction)inst).getCPInstructionType() == CPType.Variable 
					&& ((VariableCPInstruction)inst).isRemoveVariable(varName) ) {
				deleteInst.remove(i);
			}
		}
	}
	
	/**
	 * Generate rmvar instructions for the inputs, if their consumer count becomes zero.
	 * 
	 * @param node low-level operator
	 * @param inst list of instructions
	 * @param delteInst list of instructions
	 */
	private static void processConsumersForInputs(Lop node, List<Instruction> inst, List<Instruction> delteInst,
			Set<String> protectedLabels) {
		// The asynchronous instructions execute lazily. The inputs to an asynchronous instruction
		// must live till the outputs of the async. instruction are consumed (i.e. future.get is called)
		if (node.isAsynchronousOp())
			return;

		// reduce the consumer count for all input lops
		// if the count becomes zero, then variable associated w/ input can be removed
		for(Lop in : node.getInputs() )
			processConsumers(in, inst, delteInst, null, protectedLabels);
	}
	
	private static void processConsumers(Lop node, List<Instruction> inst, List<Instruction> deleteInst,
			Lop locationInfo, Set<String> protectedLabels) {
		// reduce the consumer count for all input lops
		// if the count becomes zero, then variable associated w/ input can be removed

		if ( node.removeConsumer() == 0 ) {
			// The inputs to the asynchronous input can be safely removed at this point as
			// the outputs of the asynchronous instruction are consumed.
			if (node.isAsynchronousOp())
				processConsumerIfAsync(node, inst, deleteInst, protectedLabels);

			// Federated init variables are anchored to remote data; avoid removing them
			// inside a statement block via consumer-count rmvar.
			if (node instanceof Federated)
				return;

			if ( node.isDataExecLocation() && ((Data)node).isLiteral() ) {
				return;
			}
			
			String label = node.getOutputParameters().getLabel();
			if (protectedLabels != null && label != null && protectedLabels.contains(label))
				return;
			if ("Y".equals(label) || "X".equals(label)) {
				System.out.println("[DEBUG] rmvar " + label + " from processConsumers");
			}
			Instruction currInstr = VariableCPInstruction.prepareRemoveInstruction(label);
			if (locationInfo != null)
				currInstr.setLocation(locationInfo);
			else
				currInstr.setLocation(node);
			
			inst.add(currInstr);
			excludeRemoveInstruction(label, deleteInst);
		}
	}

	// Generate rmvar instructions for the inputs of an asynchronous instruction.
	private static void processConsumerIfAsync(Lop node, List<Instruction> inst, List<Instruction> deleteInst,
			Set<String> protectedLabels) {
		if (!node.isAsynchronousOp())
			return;

		// Temporarily disable the _asynchronous flag to generate rmvars for the inputs
		node.setAsynchronous(false);
		processConsumersForInputs(node, inst, deleteInst, protectedLabels);
		node.setAsynchronous(true);
	}

	private static Set<String> collectFedInputLabels(List<Lop> execNodes) {
		Set<String> labels = new HashSet<>();
		if (execNodes == null || execNodes.isEmpty())
			return labels;
		for (Lop node : execNodes) {
			if (node == null || node.getExecType() != ExecType.FED)
				continue;
			List<Lop> inputs = node.getInputs();
			if (inputs == null || inputs.isEmpty())
				continue;
			for (Lop in : inputs) {
				if (in == null)
					continue;
				if (!(in.isDataExecLocation() || in instanceof Federated))
					continue;
				OutputParameters out = in.getOutputParameters();
				if (out != null && out.getLabel() != null)
					labels.add(out.getLabel());
			}
		}
		return labels;
	}

	private static void enforceFoutBeforeConsumers(List<Lop> execNodes) {
		if (execNodes == null || execNodes.isEmpty())
			return;
		boolean moved = true;
		while (moved) {
			moved = false;
			for (int i = 0; i < execNodes.size(); i++) {
				Lop node = execNodes.get(i);
				if (!(node instanceof FederatedFoutMaterialize))
					continue;
				int foutIdx = i;
				int maxInputIdx = -1;
				List<Lop> inputs = node.getInputs();
				if (inputs != null) {
					for (Lop in : inputs) {
						int idx = execNodes.indexOf(in);
						if (idx > maxInputIdx)
							maxInputIdx = idx;
					}
				}
				String foutLabel = (node.getOutputParameters() != null)
					? node.getOutputParameters().getLabel()
					: null;
				int minConsumerIdx = Integer.MAX_VALUE;
				for (int j = 0; j < execNodes.size(); j++) {
					if (j == foutIdx)
						continue;
					Lop cand = execNodes.get(j);
					List<Lop> candInputs = cand.getInputs();
					if (candInputs == null || candInputs.isEmpty())
						continue;
					boolean usesFout = false;
					for (Lop in : candInputs) {
						if (in == node) {
							usesFout = true;
							break;
						}
						if (foutLabel != null && in != null) {
							OutputParameters out = in.getOutputParameters();
							String inLabel = (out != null) ? out.getLabel() : null;
							if (foutLabel.equals(inLabel)) {
								usesFout = true;
								break;
							}
						}
					}
					if (usesFout)
						minConsumerIdx = Math.min(minConsumerIdx, j);
				}
				if (maxInputIdx > foutIdx) {
					execNodes.remove(foutIdx);
					int target = Math.min(maxInputIdx, execNodes.size());
					execNodes.add(target, node);
					moved = true;
					break;
				}
				if (minConsumerIdx < foutIdx) {
					int target = Math.max(maxInputIdx + 1, 0);
					if (target <= minConsumerIdx) {
						execNodes.remove(foutIdx);
						if (target > foutIdx)
							target--;
						execNodes.add(target, node);
						moved = true;
						break;
					}
				}
			}
		}
	}
	
	/**
	 * Method to generate instructions that are executed in Control Program. At
	 * this point, this DAG has no dependencies on the MR dag. ie. none of the
	 * inputs are outputs of MR jobs
	 * 
	 * @param execNodes list of low-level operators
	 * @param inst list of instructions
	 * @param writeInst list of write instructions
	 * @param deleteInst list of delete instructions
	 */
	private void generateControlProgramJobs(List<Lop> execNodes,
		List<Instruction> inst, List<Instruction> writeInst, List<Instruction> deleteInst) {

		// nodes to be deleted from execnodes
		ArrayList<Lop> markedNodes = new ArrayList<>();

		// variable names to be deleted
		ArrayList<String> var_deletions = new ArrayList<>();
		HashMap<String, Lop> var_deletionsLineNum =  new HashMap<>();
		
		boolean doRmVar = false;

		Set<String> protectedLabels = collectFedInputLabels(execNodes);
		// Re-linearize executable nodes to respect fed_fout dependencies
		// (including label-based edges) after filtering.
		execNodes = linearizeTopological(execNodes);
		enforceFoutBeforeConsumers(execNodes);
		for (int i = 0; i < execNodes.size(); i++) {
			Lop node = execNodes.get(i);
			doRmVar = false;

			// mark input scalar read nodes for deletion
			if (node.isDataExecLocation()
					&& ((Data) node).getOperationType().isRead()
					&& ((Data) node).getDataType() == DataType.SCALAR 
					&& node.getOutputParameters().getFile_name() == null ) {
				markedNodes.add(node);
				continue;
			}
			
			// output scalar instructions and mark nodes for deletion
			if (!node.isDataExecLocation()) {

				if (node.getDataType() == DataType.SCALAR) {
					// Output from lops with SCALAR data type must
					// go into Temporary Variables (Var0, Var1, etc.)
					NodeOutput out = setupNodeOutputs(node, ExecType.CP, false, false);
					inst.addAll(out.getPreInstructions()); // dummy
					deleteInst.addAll(out.getLastInstructions());
				} else {
					// Output from lops with non-SCALAR data type must
					// go into Temporary Files (temp0, temp1, etc.)
					
					NodeOutput out = setupNodeOutputs(node, ExecType.CP, false, false);
					inst.addAll(out.getPreInstructions());
					
					boolean hasTransientWriteParent = false;
					for ( Lop parent : node.getOutputs() ) {
						if ( parent.isDataExecLocation() 
								&& ((Data)parent).getOperationType().isWrite() 
								&& ((Data)parent).getOperationType().isTransient() ) {
							hasTransientWriteParent = true;
							break;
						}
					}
					
					if ( !hasTransientWriteParent ) {
						deleteInst.addAll(out.getLastInstructions());
					} 
					else {
						// Federated init outputs represent actual variables; do not treat them as temp labels.
						if (!(node instanceof Federated)) {
							String label = node.getOutputParameters().getLabel();
							if ("X".equals(label) || "Y".equals(label)) {
								System.out.println("[DEBUG] var_deletions add " + label + " from node " + node.getClass().getSimpleName()
									+ " type=" + node.getType() + " exec=" + node.getExecType());
							}
							var_deletions.add(label);
							var_deletionsLineNum.put(label, node);
						}
					}
				}

				String inst_string = "";

				// Lops with arbitrary number of inputs (ParameterizedBuiltin, GroupedAggregate, DataGen)
				// are handled separately, by simply passing ONLY the output variable to getInstructions()
				if (node.getType() == Lop.Type.ParameterizedBuiltin
						|| node.getType() == Lop.Type.GroupedAgg 
						|| node.getType() == Lop.Type.DataGen){
					inst_string = node.getInstructions(node.getOutputParameters().getLabel());
				} 
				
				// Lops with arbitrary number of inputs and outputs are handled
				// separately as well by passing arrays of inputs and outputs
				else if ( node.getType() == Lop.Type.FunctionCallCP )
				{
					String[] inputs = new String[node.getInputs().size()];
					String[] outputs = new String[node.getOutputs().size()];
					int count = 0;
					for( Lop in : node.getInputs() )
						inputs[count++] = in.getOutputParameters().getLabel();
					count = 0;
					for( Lop out : node.getOutputs() )
						outputs[count++] = out.getOutputParameters().getLabel();
					inst_string = node.getInstructions(inputs, outputs);
				}
				else if (node.getType() == Lop.Type.Nary) {
					String[] inputs = new String[node.getInputs().size()];
					int count = 0;
					for( Lop in : node.getInputs() )
						inputs[count++] = in.getOutputParameters().getLabel();
					inst_string = node.getInstructions(inputs, 
						node.getOutputParameters().getLabel());
				}
				else {
					if ( node.getInputs().isEmpty() ) {
						// currently, such a case exists only for Rand lop
						inst_string = node.getInstructions(node.getOutputParameters().getLabel());
					}
					else if (node.getInputs().size() == 1) {
						inst_string = node.getInstructions(node.getInputs()
								.get(0).getOutputParameters().getLabel(),
								node.getOutputParameters().getLabel());
					} 
					else if (node.getInputs().size() == 2) {
						inst_string = node.getInstructions(
								node.getInputs().get(0).getOutputParameters().getLabel(),
								node.getInputs().get(1).getOutputParameters().getLabel(),
								node.getOutputParameters().getLabel());
					} 
					else if (node.getInputs().size() == 3 || node.getType() == Type.Ctable) {
						inst_string = node.getInstructions(
								node.getInputs().get(0).getOutputParameters().getLabel(),
								node.getInputs().get(1).getOutputParameters().getLabel(),
								node.getInputs().get(2).getOutputParameters().getLabel(),
								node.getOutputParameters().getLabel());
					}
					else if (node.getInputs().size() == 4) {
						inst_string = node.getInstructions(
								node.getInputs().get(0).getOutputParameters().getLabel(),
								node.getInputs().get(1).getOutputParameters().getLabel(),
								node.getInputs().get(2).getOutputParameters().getLabel(),
								node.getInputs().get(3).getOutputParameters().getLabel(),
								node.getOutputParameters().getLabel());
					}
					else if (node.getInputs().size() == 5) {
						inst_string = node.getInstructions(
								node.getInputs().get(0).getOutputParameters().getLabel(),
								node.getInputs().get(1).getOutputParameters().getLabel(),
								node.getInputs().get(2).getOutputParameters().getLabel(),
								node.getInputs().get(3).getOutputParameters().getLabel(),
								node.getInputs().get(4).getOutputParameters().getLabel(),
								node.getOutputParameters().getLabel());
					}
					else if (node.getInputs().size() == 6) {
						inst_string = node.getInstructions(
								node.getInputs().get(0).getOutputParameters().getLabel(),
								node.getInputs().get(1).getOutputParameters().getLabel(),
								node.getInputs().get(2).getOutputParameters().getLabel(),
								node.getInputs().get(3).getOutputParameters().getLabel(),
								node.getInputs().get(4).getOutputParameters().getLabel(),
								node.getInputs().get(5).getOutputParameters().getLabel(),
								node.getOutputParameters().getLabel());
					}
					else if (node.getInputs().size() == 7) {
						inst_string = node.getInstructions(
								node.getInputs().get(0).getOutputParameters().getLabel(),
								node.getInputs().get(1).getOutputParameters().getLabel(),
								node.getInputs().get(2).getOutputParameters().getLabel(),
								node.getInputs().get(3).getOutputParameters().getLabel(),
								node.getInputs().get(4).getOutputParameters().getLabel(),
								node.getInputs().get(5).getOutputParameters().getLabel(),
								node.getInputs().get(6).getOutputParameters().getLabel(),
								node.getOutputParameters().getLabel());
					}
					else {
						String[] inputs = new String[node.getInputs().size()];
						for( int j=0; j<node.getInputs().size(); j++ )
							inputs[j] = node.getInputs().get(j).getOutputParameters().getLabel();
						inst_string = node.getInstructions(inputs,
								node.getOutputParameters().getLabel());
					}
				}
				
				try {
					if (LOG_LOP_MAPPING) {
						String instTrimmed = inst_string.trim();
						String outLabel = node.getOutputParameters() != null
							? node.getOutputParameters().getLabel()
							: "null";
						StringBuilder inputInfo = new StringBuilder();
						for (int j = 0; j < node.getInputs().size(); j++) {
							Lop in = node.getInputs().get(j);
							if (j > 0)
								inputInfo.append(", ");
							String inLabel = in.getOutputParameters() != null
								? in.getOutputParameters().getLabel()
								: "null";
							inputInfo.append(inLabel).append("(hop=").append(in.getHopID()).append(")");
						}
						System.out.println("[LOP] hop=" + node.getHopID()
							+ " type=" + node.getType()
							+ " out=" + outLabel
							+ " inputs=[" + inputInfo + "]"
							+ " fed=" + instTrimmed.startsWith("FED")
							+ " inst=" + instTrimmed);
					}
					if( LOG.isTraceEnabled() )
						LOG.trace("Generating instruction - "+ inst_string);
					Instruction currInstr = InstructionParser.parseSingleInstruction(inst_string);
					if(currInstr == null) {
						 throw new LopsException("Error parsing the instruction:" + inst_string);
					}
					if (node._beginLine != 0)
						currInstr.setLocation(node);
					else if ( !node.getOutputs().isEmpty() )
						currInstr.setLocation(node.getOutputs().get(0));
					else if ( !node.getInputs().isEmpty() )
						currInstr.setLocation(node.getInputs().get(0));
					
					inst.add(currInstr);
				} catch (Exception e) {
					throw new LopsException(node.printErrorLocation() + "Problem generating simple inst - "
							+ inst_string, e);
				}

				markedNodes.add(node);
				doRmVar = true;
			}
			else if (node.isDataExecLocation() ) {
				Data dnode = (Data)node;
				OpOpData op = dnode.getOperationType();
				
				if ( op.isWrite() ) {
					NodeOutput out = null;
						out = setupNodeOutputs(node, ExecType.CP, false, false);
						if ( dnode.getDataType() == DataType.SCALAR ) {
							// processing is same for both transient and persistent scalar writes 
							writeInst.addAll(out.getLastInstructions());
							doRmVar = false;
						}
						else {
							// setupNodeOutputs() handles both transient and persistent matrix writes 
							if ( dnode.getOperationType().isTransient() ) {
								deleteInst.addAll(out.getLastInstructions());
								doRmVar = false;
							}
							else {
								// In case of persistent write lop, write instruction will be generated 
								// and that instruction must be added to <code>inst</code> so that it gets
								// executed immediately. If it is added to <code>deleteInst</code> then it
								// gets executed at the end of program block's execution
								inst.addAll(out.getLastInstructions());
								doRmVar = true;
							}
						}
						markedNodes.add(node);
					
				}
				else {
					// generate a temp label to hold the value that is read from HDFS
					if ( node.getDataType() == DataType.SCALAR ) {
						node.getOutputParameters().setLabel(Lop.SCALAR_VAR_NAME_PREFIX + var_index.getNextID());
						String io_inst = node.getInstructions(node.getOutputParameters().getLabel(), 
								node.getOutputParameters().getFile_name());
						CPInstruction currInstr = CPInstructionParser.parseSingleInstruction(io_inst);
						currInstr.setLocation(node);
						
						inst.add(currInstr);
						
						Instruction tempInstr = VariableCPInstruction.prepareRemoveInstruction(node.getOutputParameters().getLabel());
						tempInstr.setLocation(node);
						deleteInst.add(tempInstr);
					}
					else {
						throw new LopsException("Matrix READs are not handled in CP yet!");
					}
					markedNodes.add(node);
					doRmVar = true;
				}
			}
			
			// see if rmvar instructions can be generated for node's inputs
			if(doRmVar)
				processConsumersForInputs(node, inst, deleteInst, protectedLabels);
			doRmVar = false;
		}
		
		for ( String var : var_deletions ) {
			if (protectedLabels.contains(var))
				continue;
			Instruction rmInst = VariableCPInstruction.prepareRemoveInstruction(var);
			if( LOG.isTraceEnabled() )
				LOG.trace("  Adding var_deletions: " + rmInst.toString());
			
			rmInst.setLocation(var_deletionsLineNum.get(var));
			
			deleteInst.add(rmInst);
		}

		// delete all marked nodes
		for ( Lop node : markedNodes ) {
			execNodes.remove(node);
		}
	}
	
	/**
	 * Method that determines the output format for a given node.
	 * 
	 * @param node low-level operator
	 * @param cellModeOverride override mode
	 * @return output info
	 */
	private static FileFormat getOutputFileFormat(Lop node, boolean cellModeOverride)
	{
		if ( (node.getDataType() == DataType.SCALAR && node.getExecType() == ExecType.CP) 
				|| node instanceof FunctionCallCP )
			return null;
	
		OutputParameters oparams = node.getOutputParameters();
		return oparams.getFormat();
	}
	
	private static String prepareAssignVarInstruction(Lop input, Lop node) {
		StringBuilder sb = new StringBuilder();
		
		sb.append(ExecType.CP);
		sb.append(Lop.OPERAND_DELIMITOR);
		
		sb.append("assignvar");
		sb.append(Lop.OPERAND_DELIMITOR);

		sb.append( input.prepScalarInputOperand(ExecType.CP) );
		sb.append(Lop.OPERAND_DELIMITOR);
		
		sb.append(node.prepOutputOperand());

		return sb.toString();
	}

	/**
	 * Method to setup output filenames and outputInfos, and to generate related instructions
	 * 
	 * @param node low-level operator
	 * @param et exec type
	 * @param cellModeOverride override mode
	 * @param copyTWrite ?
	 * @return node output
	 */
	private NodeOutput setupNodeOutputs(Lop node, ExecType et, boolean cellModeOverride, boolean copyTWrite) {
		
		OutputParameters oparams = node.getOutputParameters();
		NodeOutput out = new NodeOutput();
		
		node.setConsumerCount(node.getOutputs().size());

		// Compute the output format for this node
		out.setOutInfo(getOutputFileFormat(node, cellModeOverride));

		// If node is NOT of type Data then we must generate
		// a variable to hold the value produced by this node
		// note: functioncallcp requires no createvar, rmvar since
		// since outputs are explicitly specified
		if( !node.isDataExecLocation() ) 
		{
			if (node.getDataType() == DataType.SCALAR || node.getDataType() == DataType.LIST) {
				if (oparams.getLabel() == null)
					oparams.setLabel(Lop.SCALAR_VAR_NAME_PREFIX + var_index.getNextID());
				Instruction currInstr = VariableCPInstruction.prepareRemoveInstruction(oparams.getLabel());
				
				currInstr.setLocation(node);
				out.addLastInstruction(currInstr);
			}
			else if(!(node instanceof FunctionCallCP)) //general case
			{
				// generate temporary filename and a variable name to hold the
				// output produced by "rootNode"
				if (oparams.getFile_name() == null)
					oparams.setFile_name(getNextUniqueFilename());
				if (oparams.getLabel() == null)
					oparams.setLabel(getNextUniqueVarname(node.getDataType()));

				// generate an instruction that creates a symbol table entry for the new variable
				//String createInst = prepareVariableInstruction("createvar", node);
				//out.addPreInstruction(CPInstructionParser.parseSingleInstruction(createInst));
				int blen = (int) oparams.getBlocksize();
				Instruction createvarInst = VariableCPInstruction.prepCreatevarInstruction(
					oparams.getLabel(), oparams.getFile_name(), true, node.getDataType(),
					getOutputFileFormat(node, false).toString(),
					new MatrixCharacteristics(oparams.getNumRows(), oparams.getNumCols(), blen, oparams.getNnz()),
					oparams.getUpdateType());
				
				createvarInst.setLocation(node);
				out.addPreInstruction(createvarInst);

				// temp file as well as the variable has to be deleted at the end
				if (!(node instanceof Federated)) {
					Instruction currInstr = VariableCPInstruction.prepareRemoveInstruction(oparams.getLabel());
					currInstr.setLocation(node);
					out.addLastInstruction(currInstr);
				}
			}
			else {
				// If the function call is set with output lops (e.g., multi return builtin),
				// generate a createvar instruction for each function output
				// (except for remove, which creates list outputs, i.e., meta data objects)
				FunctionCallCP fcall = (FunctionCallCP) node;
				if ( fcall.getFunctionOutputs() != null && fcall.requiresOutputCreateVar() ) {
					for( Lop fnOut: fcall.getFunctionOutputs()) {
						OutputParameters fnOutParams = fnOut.getOutputParameters();
						//OutputInfo oinfo = getOutputInfo((N)fnOut, false);
						Instruction createvarInst = VariableCPInstruction.prepCreatevarInstruction(
							fnOutParams.getLabel(), getFilePath() + fnOutParams.getLabel(), 
							true, fnOut.getDataType(), getOutputFileFormat(fnOut, false).toString(),
							new MatrixCharacteristics(fnOutParams.getNumRows(), fnOutParams.getNumCols(), (int)fnOutParams.getBlocksize(), fnOutParams.getNnz()),
							oparams.getUpdateType());
						
						if (node._beginLine != 0)
							createvarInst.setLocation(node);
						else
							createvarInst.setLocation(fnOut);
						out.addPreInstruction(createvarInst);
					}
				}
			}
		}
		// rootNode is of type Data
		else {
			
			if ( node.getDataType() == DataType.SCALAR ) {
				// generate assignment operations for final and transient writes
				if ( oparams.getFile_name() == null && !(node instanceof Data && ((Data)node).isPersistentWrite()) ) {
					String io_inst = prepareAssignVarInstruction(node.getInputs().get(0), node);
					CPInstruction currInstr = CPInstructionParser.parseSingleInstruction(io_inst);
					
					if (node._beginLine != 0)
						currInstr.setLocation(node);
					else if ( !node.getInputs().isEmpty() )
						currInstr.setLocation(node.getInputs().get(0));
					
					out.addLastInstruction(currInstr);
				}
				else {
					//CP PERSISTENT WRITE SCALARS
					Lop fname = ((Data)node).getNamedInputLop(DataExpression.IO_FILENAME);
					String io_inst = node.getInstructions(node.getInputs().get(0).getOutputParameters().getLabel(), fname.getOutputParameters().getLabel());
					CPInstruction currInstr = CPInstructionParser.parseSingleInstruction(io_inst);
					
					if (node._beginLine != 0)
						currInstr.setLocation(node);
					else if ( !node.getInputs().isEmpty() )
						currInstr.setLocation(node.getInputs().get(0));
					
					out.addLastInstruction(currInstr);
				}
			}
			else {
				if ( ((Data)node).getOperationType().isTransient() ) {
					
					if ( et == ExecType.CP ) {
						// If transient matrix write is in CP then its input MUST be executed in CP as well.
						
						// get variable and filename associated with the input
						String inputVarName = node.getInputs().get(0).getOutputParameters().getLabel();
						
						String constVarName = oparams.getLabel();
						
						/*
						 * Symbol Table state must change as follows:
						 * 
						 * FROM:
						 *     mvar1 -> temp21
						 *  
						 * TO:
						 *     mVar1 -> temp21
						 *     tVarH -> temp21
						 */
						Instruction currInstr = VariableCPInstruction.prepareCopyInstruction(inputVarName, constVarName);
						
						currInstr.setLocation(node);
						
						out.addLastInstruction(currInstr);
					}
					else {
						if(copyTWrite) {
							Instruction currInstr = VariableCPInstruction.prepareCopyInstruction(node.getInputs().get(0).getOutputParameters().getLabel(), oparams.getLabel());
							
							currInstr.setLocation(node);
							
							out.addLastInstruction(currInstr);
							return out;
						}
						
						/*
						 * Since the "rootNode" is a transient data node, we first need to generate a 
						 * temporary filename as well as a variable name to hold the <i>immediate</i> 
						 * output produced by "rootNode". These generated HDFS filename and the 
						 * variable name must be changed at the end of an iteration/program block 
						 * so that the subsequent iteration/program block can correctly access the 
						 * generated data. Therefore, we need to distinguish between the following:
						 * 
						 *   1) Temporary file name & variable name: They hold the immediate output 
						 *   produced by "rootNode". Both names are generated below.
						 *   
						 *   2) Constant file name & variable name: They are constant across iterations. 
						 *   Variable name is given by rootNode's label that is created in the upper layers.  
						 *   File name is generated by concatenating "temporary file name" and "constant variable name".
						 *   
						 * Temporary files must be moved to constant files at the end of the iteration/program block.
						 */
						
						// generate temporary filename & var name
						String tempVarName = oparams.getLabel() + "temp";
						String tempFileName = getNextUniqueFilename();
						
						int blen = (int) oparams.getBlocksize();
						Instruction createvarInst = VariableCPInstruction.prepCreatevarInstruction(
							tempVarName, tempFileName, true, node.getDataType(), out.getOutInfo().toString(), 
							new MatrixCharacteristics(oparams.getNumRows(), oparams.getNumCols(), blen, oparams.getNnz()),
							oparams.getUpdateType());
						
						createvarInst.setLocation(node);
						
						out.addPreInstruction(createvarInst);

						
						String constVarName = oparams.getLabel();
						String constFileName = tempFileName + constVarName;
						
						oparams.setFile_name(getFilePath() + constFileName);
						
						/*
						 * Since this is a node that denotes a transient read/write, we need to make sure 
						 * that the data computed for a given variable in a given iteration is passed on 
						 * to the next iteration. This is done by generating miscellaneous instructions 
						 * that gets executed at the end of the program block.
						 * 
						 * The state of the symbol table must change 
						 * 
						 * FROM: 
						 *     tVarA -> temp21tVarA (old copy of temp21)
						 *     tVarAtemp -> temp21  (new copy that should override the old copy) 
						 *
						 * TO:
						 *     tVarA -> temp21tVarA
						 */
						
						// Generate a single mvvar instruction (e.g., mvvar tempA A) 
						//    instead of two instructions "cpvar tempA A" and "rmvar tempA"
						Instruction currInstr = VariableCPInstruction.prepMoveInstruction(tempVarName, constVarName);
						
						currInstr.setLocation(node);
						
						out.addLastInstruction(currInstr);
					}
				} 
				// rootNode is not a transient write. It is a persistent write.
				else {
					{ //CP PERSISTENT WRITE
						// generate a write instruction that writes matrix to HDFS
						Lop fname = ((Data)node).getNamedInputLop(DataExpression.IO_FILENAME);
						
						String io_inst = node.getInstructions(
							node.getInputs().get(0).getOutputParameters().getLabel(), 
							fname.getOutputParameters().getLabel());
						Instruction currInstr = (node.getExecType() == ExecType.SPARK) ?
							SPInstructionParser.parseSingleInstruction(io_inst) :
							CPInstructionParser.parseSingleInstruction(io_inst);
						Lop useNode = (!node.getInputs().isEmpty() 
							&& node.getInputs().get(0)._beginLine != 0) ? node.getInputs().get(0) : node; 
						currInstr.setLocation(useNode);
						
						out.addLastInstruction(currInstr);
					}
				}
			}
		}
		
		return out;
	}

	/**
	 * Performs various cleanups on the list of instructions in order to reduce the
	 * number of instructions to simply debugging and reduce interpretation overhead. 
	 * 
	 * @param insts list of instructions
	 * @return new list of potentially modified instructions
	 */
	private static ArrayList<Instruction> cleanupInstructions(List<Instruction> insts) {
		//step 1: create mvvar instructions: assignvar s1 s2, rmvar s1 -> mvvar s1 s2,
		//                                   cpvar m1 m2, rmvar m1 --> mvvar m1 m2
		//                                   rmvar m2, mvvar m1 m2 -> mvvar m1 m2
		List<Instruction> tmp1 = collapseAssignvarAndRmvarInstructions(insts);
		
		//step 2: create packed rmvar instructions: rmvar m1, rmvar m2 -> rmvar m1 m2
		ArrayList<Instruction> tmp2 = createPackedRmvarInstructions(tmp1);
		
		return tmp2;
	}
	
	private static List<Instruction> collapseAssignvarAndRmvarInstructions(List<Instruction> insts) {
		ArrayList<Instruction> ret = new ArrayList<>();
		Iterator<Instruction> iter = insts.iterator();
		while( iter.hasNext() ) {
			Instruction inst = iter.next();
			if( iter.hasNext() && inst instanceof VariableCPInstruction
				&& ((VariableCPInstruction)inst).isAssignOrCopyVariable() ) {
				VariableCPInstruction inst1 = (VariableCPInstruction) inst;
				Instruction inst2 = iter.next();
				if( inst2 instanceof VariableCPInstruction
					&& ((VariableCPInstruction)inst2).isRemoveVariableNoFile()
					&& inst1.getInput1().getName().equals(
						((VariableCPInstruction)inst2).getInput1().getName()) ) {
					//remove unnecessary rmvar before mvvar
					Instruction last = ret.size()>0 ? ret.get(ret.size()-1) : null;
					if( last != null && last instanceof VariableCPInstruction
						&& ((VariableCPInstruction)last).isRemoveVariableNoFile()
						&& ((VariableCPInstruction)last).getInputs().size() == 1
						&& ((VariableCPInstruction)last).getInput1().getName().equals(inst1.getInput2().getName()))
						ret.remove(ret.size()-1);
					//add fused mvvar instruction
					ret.add(VariableCPInstruction.prepMoveInstruction(
						inst1.getInput1().getName(), inst1.getInput2().getName()));
				}
				else {
					ret.add(inst1);
					ret.add(inst2);
				}
			}
			else {
				ret.add(inst);
			}
		}
		return ret;
	}
	
	private static ArrayList<Instruction> createPackedRmvarInstructions(List<Instruction> insts) {
		ArrayList<Instruction> ret = new ArrayList<>();
		ArrayList<String> currRmVar = new ArrayList<>();
		for( Instruction inst : insts ) {
			if( inst instanceof VariableCPInstruction 
				&& ((VariableCPInstruction)inst).isRemoveVariableNoFile() ) {
				//collect all subsequent rmvar instructions
				currRmVar.add(((VariableCPInstruction)inst).getInput1().getName());
			}
			else {
				//construct packed rmvar instruction
				if( !currRmVar.isEmpty() ) {
					if (currRmVar.contains("Y") || currRmVar.contains("X")) {
						System.out.println("[DEBUG] packed rmvar contains Y/X: " + currRmVar);
					}
					ret.add(VariableCPInstruction.prepareRemoveInstruction(
						currRmVar.toArray(new String[0])));
					currRmVar.clear();
				}
				//add other instruction
				ret.add(inst);
			}
		}
		//construct last packed rmvar instruction
		if( !currRmVar.isEmpty() ) {
			if (currRmVar.contains("Y") || currRmVar.contains("X")) {
				System.out.println("[DEBUG] packed rmvar contains Y/X: " + currRmVar);
			}
			ret.add(VariableCPInstruction.prepareRemoveInstruction(
				currRmVar.toArray(new String[0])));
		}
		return ret;
	}
}

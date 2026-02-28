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

package org.apache.sysds.hops.fedplanner.fedCostBased;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Iterator;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.InitFEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.VariableSet;
import org.apache.sysds.runtime.meta.MetaDataAll;
import org.apache.sysds.runtime.io.IOUtilFunctions;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Map.Entry;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Utility class for federated planners.
 */
public class FederatedPlannerUtils {
	private static final String FED_MATRIX_IDENTIFIER = "matrix";
	private static final java.util.Set<String> FED_INIT_VARS = ConcurrentHashMap.newKeySet();
	private static final Map<String, FType> FED_INIT_FTYPES = new ConcurrentHashMap<>();
	private static final Map<String, String> FED_INIT_SIGNATURES = new ConcurrentHashMap<>();
	private static final Map<String, String> FED_ANCHOR_KEYS = new ConcurrentHashMap<>();
	private static final java.util.Set<String> FED_RMVAR_PROTECTED_VARS = ConcurrentHashMap.newKeySet();

	/**
	 * Get transient inputs from either paramMap or transientWrites.
	 * Inputs from paramMap has higher priority than inputs from transientWrites.
	 * 
	 * @param currentHop       hop for which inputs are read from maps
	 * @param paramMap         of local parameters
	 * @param transientWrites  map of transient writes
	 * @param localVariableMap map of local variables
	 * @return inputs of currentHop
	 */
	public static ArrayList<Hop> getTransientInputs(Hop currentHop, Map<String, Hop> paramMap,
			Map<String, Hop> transientWrites, LocalVariableMap localVariableMap) {
		Hop tWriteHop = null;
		if (paramMap != null)
			tWriteHop = paramMap.get(currentHop.getName());
		if (tWriteHop == null)
			tWriteHop = transientWrites.get(currentHop.getName());
		if (tWriteHop == null) {
			if (localVariableMap.get(currentHop.getName()) != null)
				return null;
			else
				throw new DMLRuntimeException("Transient write not found for " + currentHop);
		} else
			return new ArrayList<>(Collections.singletonList(tWriteHop));
	}

	/**
	 * Return parameter map containing the mapping from parameter name to input hop
	 * for all parameters of the function hop.
	 * 
	 * @param funcOp hop for which the mapping of parameter names to input hops are
	 *               made
	 * @return parameter map or empty map if function has no parameters
	 */
	public static Map<String, Hop> getParamMap(FunctionOp funcOp) {
		String[] inputNames = funcOp.getInputVariableNames();
		Map<String, Hop> paramMap = new HashMap<>();
		if (inputNames != null) {
			for (int i = 0; i < funcOp.getInput().size(); i++)
				paramMap.put(inputNames[i], funcOp.getInput(i));
		}
		return paramMap;
	}

	public static boolean isVectorShape(Hop hop) {
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix())
			return false;
		if (!hop.dimsKnown())
			return false;
		long rlen = hop.getDim1();
		long clen = hop.getDim2();
		if (rlen <= 0 || clen <= 0)
			return false;
		if (rlen == 1 && clen == 1)
			return false;
		return (rlen == 1 && clen > 1) || (clen == 1 && rlen > 1);
	}

	public static boolean isScalarLikeMatrix(Hop hop) {
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix())
			return false;
		if (!hop.dimsKnown())
			return false;
		return hop.getDim1() == 1 && hop.getDim2() == 1;
	}

	public static FType getVectorAxis(Hop hop) {
		if (!isVectorShape(hop))
			return null;
		long rlen = hop.getDim1();
		long clen = hop.getDim2();
		if (rlen == 1 && clen > 1)
			return FType.COL;
		if (clen == 1 && rlen > 1)
			return FType.ROW;
		return null;
	}

	public static double computeForwardingWeightOfChild(double networkWeight,
			List<Pair<Long, Double>> parentLoopContext, List<Pair<Long, Double>> childLoopContext) {
		return computeForwardingWeightOfChild(networkWeight, parentLoopContext, childLoopContext, 1.0);
	}

	public static double computeForwardingWeightOfChild(double networkWeight,
			List<Pair<Long, Double>> parentLoopContext, List<Pair<Long, Double>> childLoopContext,
			double consumerMultiplicity) {
		double base = (networkWeight != 0.0) ? networkWeight : 1.0;

		if (parentLoopContext == null || parentLoopContext.isEmpty())
			return base * Math.max(consumerMultiplicity, 0.0);

		Map<Long, Double> childMap = new HashMap<>();
		if (childLoopContext != null) {
			for (Pair<Long, Double> p : childLoopContext)
				childMap.put(p.getLeft(), p.getRight());
		}

		double weight = base;
		for (Pair<Long, Double> p : parentLoopContext) {
			long loopId = p.getLeft();
			double iters = p.getRight();
			if (!childMap.containsKey(loopId) && iters > 0.0)
				weight /= iters;
		}

		return weight * Math.max(consumerMultiplicity, 0.0);
	}

	public static final class CompatibilityScore {
		public final int priority;
		public final int score;
		public final int nameScore;

		public CompatibilityScore(int priority, int score, int nameScore) {
			this.priority = priority;
			this.score = score;
			this.nameScore = nameScore;
		}

		public boolean isBetterThan(CompatibilityScore other) {
			if (other == null)
				return true;
			if (priority != other.priority)
				return priority < other.priority;
			if (score != other.score)
				return score < other.score;
			return nameScore < other.nameScore;
		}
	}

	public static void wireUnRefTwriteToLiveOutCommon(
			StatementBlock sb, Set<Long> unRefTwriteSet,
			Function<Long, Hop> hopLookup, Map<String, List<Hop>> newFormerTransTable,
			BiFunction<Hop, Hop, CompatibilityScore> compatibilityFn, String logPrefix) {

		if (unRefTwriteSet.isEmpty())
			return;

		VariableSet genHops = sb.getGen();
		VariableSet updatedHops = sb.variablesUpdated();
		VariableSet liveOutHops = sb.liveOut();

		Iterator<Long> unRefTwriteIterator = unRefTwriteSet.iterator();
		while (unRefTwriteIterator.hasNext()) {
			Long unRefTwriteHopID = unRefTwriteIterator.next();
			Hop unRefTwriteHop = hopLookup.apply(unRefTwriteHopID);
			if (unRefTwriteHop == null) {
				FederatedPlannerLogger.logWarnMessage(logPrefix + " Skipping unRefTwrite hop "
						+ unRefTwriteHopID + " because hop reference is missing");
				unRefTwriteIterator.remove();
				continue;
			}

			String unRefTwriteHopName = unRefTwriteHop.getName();

			if (liveOutHops.containsVariable(unRefTwriteHopName)) {
				continue;
			}

			if (unRefTwriteHop instanceof FunctionOp || genHops.containsVariable(unRefTwriteHopName)
					|| updatedHops.containsVariable(unRefTwriteHopName)) {

				String bestLiveOutHopName = null;
				CompatibilityScore bestScore = null;

				Iterator<String> liveOutHopsIterator = liveOutHops.getVariableNames().iterator();
				while (liveOutHopsIterator.hasNext()) {
					String liveOutHopName = liveOutHopsIterator.next();
					List<Hop> liveOutHopsList = newFormerTransTable.get(liveOutHopName);

					if (liveOutHopsList == null || liveOutHopsList.isEmpty()) {
						continue;
					}

					Hop representativeLiveOutHop = liveOutHopsList.get(0);
					if (representativeLiveOutHop == null) {
						continue;
					}

					CompatibilityScore compatScore = compatibilityFn.apply(unRefTwriteHop, representativeLiveOutHop);

					if (compatScore != null && compatScore.isBetterThan(bestScore)) {
						bestScore = compatScore;
						bestLiveOutHopName = liveOutHopName;
					}
				}

				if (bestLiveOutHopName == null) {
					throw new DMLRuntimeException("No liveOutHops found for " + unRefTwriteHopName + " (hopID="
							+ unRefTwriteHop.getHopID() + ", opcode=" + unRefTwriteHop.getOpString() + ")");
				}

				List<Hop> bestLiveOutHopsList = newFormerTransTable.get(bestLiveOutHopName);
				List<Hop> copyLiveOutHopsList = new ArrayList<>(bestLiveOutHopsList);
				copyLiveOutHopsList.add(unRefTwriteHop);
				newFormerTransTable.put(bestLiveOutHopName, copyLiveOutHopsList);
				unRefTwriteIterator.remove();
			}
		}
	}

	// NOTE: keep privacy semantics in sync with DP planner.
	public static Privacy getFedWorkerMetaData(
			List<Pair<FederatedRange, FederatedData>> fedMap, DataOp initFedOp) {
		final boolean hasLocalObject = initFedOp.hasParameter(DataExpression.FED_LOCAL_OBJECT);
		final Hop localObjectHop = hasLocalObject
				? initFedOp.getInput(initFedOp.getParameterIndex(DataExpression.FED_LOCAL_OBJECT))
				: null;

		// For `federated(local_matrix=...)`, addresses are allowed to omit the file path (host:port),
		// and privacy must be derived locally (workers do not own a stable on-disk file at compile-time).
		// Do not issue compile-time READ_VAR/privacy RPCs in this case because they would force a
		// spurious worker-side file read of a coordinator-local scratch path.
		final String localObjectFilePath = (localObjectHop instanceof DataOp)
				? ((DataOp) localObjectHop).getFileName()
				: null;
		final String localInitFilePath = (localObjectFilePath != null && !localObjectFilePath.isEmpty())
				? localObjectFilePath
				: (initFedOp.getName() != null ? initFedOp.getName() : "__local_matrix__");

		// Address
		Hop addressListHop = initFedOp.getInput(initFedOp.getParameterIndex("addresses"));
		List<String> addressList = new ArrayList<>();
		for (Hop addressHop : addressListHop.getInput()) {
			addressList.add(addressHop.getName());
		}

		// Range
		Hop rangeListHop = initFedOp.getInput(initFedOp.getParameterIndex("ranges"));
		List<long[]> rangeList = new ArrayList<>();
		for (Hop rangeHop : rangeListHop.getInput()) {
			long beginRange = (long) Double.parseDouble(rangeHop.getInput(0).getName());
			long endRange = (long) Double.parseDouble(rangeHop.getInput(1).getName());
			rangeList.add(new long[] { beginRange, endRange });
		}

		// Type
		String type = initFedOp.getInput(initFedOp.getParameterIndex("type")).getName();
		Types.DataType fedDataType = type.equalsIgnoreCase(FED_MATRIX_IDENTIFIER)
				? Types.DataType.MATRIX
				: Types.DataType.FRAME;

		// Local list for privacy calculation of this DataOp only
		List<FedWorkerContext> localWorkers = new ArrayList<>();

		// Init Fed Data
		for (int i = 0; i < addressList.size(); i++) {
			String address = addressList.get(i);
			// We split address into url/ip, the port and (optionally) file path of file to read.
			final String host;
			final int port;
			final String filePath;
			if (hasLocalObject) {
				String[] parsedValues = InitFEDInstruction.parseURLNoFilePath(address);
				host = parsedValues[0];
				port = Integer.parseInt(parsedValues[1]);
				filePath = localInitFilePath;
			} else {
				String[] parsedValues = InitFEDInstruction.parseURL(address);
				host = parsedValues[0];
				port = Integer.parseInt(parsedValues[1]);
				filePath = parsedValues[2];
			}

			long[] beginRange = rangeList.get(2 * i);
			long[] endRange = rangeList.get(2 * i + 1);

			try {
				FederatedData federatedData = new FederatedData(fedDataType,
						new InetSocketAddress(InetAddress.getByName(host), port), filePath);
				FederatedRange range = new FederatedRange(beginRange, endRange);
				Pair<FederatedRange, FederatedData> pair = new ImmutablePair<>(range, federatedData);

				fedMap.add(pair); // Global worker count approximation
				localWorkers.add(new FedWorkerContext(host, port, filePath, federatedData));
			} catch (UnknownHostException e) {
				throw new DMLRuntimeException("federated host was unknown: " + host, e);
			}
		}
		Privacy privacyConstraint = null;
		boolean hadPrivacyFailure = false;

		if (hasLocalObject) {
			// Best-effort: derive privacy from local metadata (if available); otherwise default to PUBLIC.
			Privacy derived = null;
			if (localObjectFilePath != null && !localObjectFilePath.isEmpty()) {
				try {
					derived = parsePrivacyConstraint(readPrivacyConstraintsFromLocalMTD(localObjectFilePath));
				} catch (Exception ex) {
					derived = null;
				}
			}
			privacyConstraint = (derived != null) ? derived : Privacy.PUBLIC;
		}

		// Request Privacy Constraints.
		// Privacy is derived only from this DataOp's workers (localWorkers).
		for (FedWorkerContext wctx : localWorkers) {
			if (hasLocalObject) // do not contact workers for local_matrix-fedinit (see above)
				break;
			FederatedData data = wctx.data;
			if (!data.isInitialized())
				data.initFederatedData(FederationUtils.getNextFedDataID());

			Future<FederatedResponse> future = data.requestPrivacyConstraints();
			try {
				FederatedResponse response = future.get(); // Get actual response from Future

				if (response.isSuccessful()) {
					Object[] responseData = response.getData();
					String privacyConstraints = (String) responseData[0]; // Cast privacy constraint as string

					if (privacyConstraints == null) {
						String fallback = tryLocalPrivacyFallback(wctx);
						if (fallback != null) {
							FederatedPlannerLogger.logWarnMessage(
									"[FederatedPlanner] Falling back to local metadata privacy constraints for "
											+ wctx.host + ":" + wctx.port + " (" + wctx.filePath + ")");
							privacyConstraint = mergePrivacyConstraint(privacyConstraint, fallback);
							continue;
						}
						String msg = "Worker " + wctx.host + ":" + wctx.port + " (" + wctx.filePath
								+ ") returned null privacy constraints for FEDERATED data op '" + initFedOp.getName()
								+ "' (hopID=" + initFedOp.getHopID() + ")";
						FederatedPlannerLogger.logErrorMessage("[FederatedPlanner] " + msg);
						hadPrivacyFailure = true;
						continue;
					}

					privacyConstraint = mergePrivacyConstraint(privacyConstraint, privacyConstraints);
				} else {
					// Error handling: treat any unsuccessful response as fatal for planning
					String errorMsg = response.getErrorMessage();
					FederatedPlannerLogger.logErrorMessage(
							"Failed to request privacy constraints from " + wctx.host + ":" + wctx.port + " ("
									+ wctx.filePath
									+ ") for FEDERATED data op '" + initFedOp.getName() + "' (hopID="
									+ initFedOp.getHopID()
									+ "): " + errorMsg);
					String fallback = tryLocalPrivacyFallback(wctx);
					if (fallback != null) {
						FederatedPlannerLogger.logWarnMessage(
								"[FederatedPlanner] Falling back to local metadata privacy constraints for "
										+ wctx.host + ":" + wctx.port + " (" + wctx.filePath + ")");
						privacyConstraint = mergePrivacyConstraint(privacyConstraint, fallback);
					} else {
						hadPrivacyFailure = true;
					}
				}
			} catch (Exception e) {
				String fallback = tryLocalPrivacyFallback(wctx);
				if (fallback != null) {
					FederatedPlannerLogger.logWarnMessage(
							"[FederatedPlanner] Falling back to local metadata privacy constraints for "
									+ wctx.host + ":" + wctx.port + " (" + wctx.filePath + ") after request failure");
					privacyConstraint = mergePrivacyConstraint(privacyConstraint, fallback);
				} else {
					// Exception handling: also treated as fatal for planning
					String errorContext = "Failed to request privacy constraints from " + wctx.host + ":" + wctx.port
							+ " ("
							+ wctx.filePath + ") for FEDERATED data op '" + initFedOp.getName() + "' (hopID="
							+ initFedOp.getHopID() + ")";
					FederatedPlannerLogger.logException(errorContext, e);
					hadPrivacyFailure = true;
				}
			}
		}
		if (privacyConstraint == null || hadPrivacyFailure) {
			String errorMsg = "One or more federated workers failed to provide valid privacy constraints for FEDERATED data op '"
					+ initFedOp.getName() + "' (hopID=" + initFedOp.getHopID()
					+ "); cannot safely plan federated execution.";
			FederatedPlannerLogger.logErrorMessage("[FederatedPlanner] " + errorMsg + " Aborting planning.");
			throw new DMLRuntimeException(errorMsg);
		}
		FType fedInitFType = deriveFedInitFType(initFedOp);
		String fedInitSignature = deriveFedInitSignature(initFedOp);
		registerFedInitVar(initFedOp.getName(), fedInitFType, fedInitSignature);
		return privacyConstraint;
	}

	public static void registerFedInitVar(String varName) {
		registerFedInitVar(varName, null);
	}

	public static void registerFedInitVar(String varName, FType fedInitFType) {
		registerFedInitVar(varName, fedInitFType, null);
	}

	public static void registerFedInitVar(String varName, FType fedInitFType, String signature) {
		if (varName != null && !varName.isEmpty()) {
			FED_INIT_VARS.add(varName);
			if (fedInitFType != null)
				FED_INIT_FTYPES.put(varName, fedInitFType);
			if (signature != null)
				FED_INIT_SIGNATURES.put(varName, signature);
			if (signature != null) {
				// Signature-based anchor keys must carry a concrete FType suffix so runtime
				// refederation can rebuild a worker-pool anchor from the literal key.
				// If the fType is unknown at planning time, default to FULL (safe fallback).
				FType effectiveType = (fedInitFType != null) ? fedInitFType : FType.FULL;
				String key = signature + "|" + effectiveType.name();
				registerFedAnchorKey(varName, key);
			}
		}
	}

	public static void clearFedInitVars() {
		FED_INIT_VARS.clear();
		FED_INIT_FTYPES.clear();
		FED_INIT_SIGNATURES.clear();
		FED_ANCHOR_KEYS.clear();
		FED_RMVAR_PROTECTED_VARS.clear();
	}

	public static void clearFedAnchorKeys() {
		FED_ANCHOR_KEYS.clear();
	}

	public static void clearFedRmvarProtectedVars() {
		FED_RMVAR_PROTECTED_VARS.clear();
	}

	public static boolean isFedInitVar(String varName) {
		return varName != null && FED_INIT_VARS.contains(varName);
	}

	public static boolean isFedRmvarProtectedVar(String varName) {
		return varName != null && FED_RMVAR_PROTECTED_VARS.contains(varName);
	}

	public static FType getFedInitFType(String varName) {
		return (varName == null) ? null : FED_INIT_FTYPES.get(varName);
	}

	public static String getFedInitSignature(String varName) {
		return (varName == null) ? null : FED_INIT_SIGNATURES.get(varName);
	}

	public static void registerFedAnchorKey(String varName, String anchorKey) {
		if (varName == null || varName.isEmpty() || anchorKey == null || anchorKey.isEmpty())
			return;
		String existing = FED_ANCHOR_KEYS.get(varName);
		if (existing != null) {
			boolean existingVarKey = existing.startsWith("VAR:");
			boolean newVarKey = anchorKey.startsWith("VAR:");
			if (!existingVarKey && newVarKey)
				return; // keep signature-based key
			if (existingVarKey && !newVarKey)
				FED_ANCHOR_KEYS.put(varName, anchorKey);
			else if (existing.equals(anchorKey))
				return;
			else
				FED_ANCHOR_KEYS.put(varName, anchorKey);
		}
		else {
			FED_ANCHOR_KEYS.put(varName, anchorKey);
		}
		org.apache.sysds.runtime.controlprogram.federated.FederationUtils.registerAnchorKey(varName, anchorKey);
	}

	public static void removeFedAnchorKey(String varName) {
		if (varName != null && !varName.isEmpty()) {
			FED_ANCHOR_KEYS.remove(varName);
			org.apache.sysds.runtime.controlprogram.federated.FederationUtils.removeAnchorKey(varName);
		}
	}

	public static void removeFedInitVar(String varName) {
		if (varName == null || varName.isEmpty())
			return;
		FED_INIT_VARS.remove(varName);
		FED_INIT_FTYPES.remove(varName);
		FED_INIT_SIGNATURES.remove(varName);
	}

	/**
	 * Scoped override for federated-init metadata keyed by variable name.
	 *
	 * <p>Function parameters in DML functions can legitimately share the same names as global
	 * federated-init variables (e.g., {@code X}, {@code Y}). Using the global fed-init registries
	 * by name inside callee rewiring can therefore produce invalid plans when the call-site
	 * passes a local expression into a parameter that happens to share a global fed-init name.
	 *
	 * <p>This helper temporarily clears (and optionally re-registers) fed-init metadata for the
	 * given parameter names based on the call-site argument hops, and restores the previous state
	 * on {@link #close()}.</p>
	 */
	public static final class ScopedFedVarOverride implements AutoCloseable {
		private final java.util.Map<String, FedVarState> _previous;
		private boolean _closed = false;

		private ScopedFedVarOverride(java.util.Map<String, FedVarState> previous) {
			_previous = (previous != null) ? previous : java.util.Collections.emptyMap();
		}

		@Override
		public void close() {
			if (_closed)
				return;
			_closed = true;
			for (java.util.Map.Entry<String, FedVarState> e : _previous.entrySet()) {
				String varName = e.getKey();
				FedVarState state = e.getValue();
				removeFedAnchorKey(varName);
				removeFedInitVar(varName);
				if (state == null)
					continue;
				if (state.fedInit) {
					registerFedInitVar(varName, state.fType, state.signature);
				}
				else if (state.anchorKey != null && !state.anchorKey.isEmpty()) {
					registerFedAnchorKey(varName, state.anchorKey);
				}
			}
		}
	}

	private static final class FedVarState {
		private final boolean fedInit;
		private final FType fType;
		private final String signature;
		private final String anchorKey;

		private FedVarState(boolean fedInit, FType fType, String signature, String anchorKey) {
			this.fedInit = fedInit;
			this.fType = fType;
			this.signature = signature;
			this.anchorKey = anchorKey;
		}
	}

	private static FedVarState captureFedVarState(String varName) {
		boolean fedInit = isFedInitVar(varName);
		FType fType = getFedInitFType(varName);
		String sig = getFedInitSignature(varName);
		String anchorKey = getFedAnchorKey(varName);
		return new FedVarState(fedInit, fType, sig, anchorKey);
	}

	private static FedVarState deriveFedVarStateFromArgument(Hop argHop) {
		if (argHop == null)
			return null;
		if (argHop instanceof DataOp) {
			DataOp dataOp = (DataOp) argHop;
			Types.OpOpData op = dataOp.getOp();
			if (op == Types.OpOpData.FEDERATED) {
				String name = dataOp.getName();
				if (name == null || name.isEmpty())
					return null;
				FType fType = getFedInitFType(name);
				String sig = getFedInitSignature(name);
				if (sig == null)
					sig = deriveFedInitSignature(dataOp);
				if (fType == null)
					fType = deriveFedInitFType(dataOp);
				String anchorKey = getFedAnchorKey(name);
				return new FedVarState(true, fType, sig, anchorKey);
			}
			if (op == Types.OpOpData.TRANSIENTREAD) {
				String name = dataOp.getName();
				if (name == null || name.isEmpty())
					return null;
				if (!isFedInitVar(name))
					return null;
				FType fType = getFedInitFType(name);
				String sig = getFedInitSignature(name);
				String anchorKey = getFedAnchorKey(name);
				return new FedVarState(true, fType, sig, anchorKey);
			}
		}
		return null;
	}

	/**
	 * Temporarily override fed-init metadata for the given function parameters based on their
	 * call-site argument hops.
	 *
	 * <p>Only arguments that are known fed-init sources (FEDERATED op, or transient-read of a
	 * registered fed-init var) are re-registered as fed-init parameters. All other parameters are
	 * treated as non-fed-init for the duration of the scope.</p>
	 */
	public static ScopedFedVarOverride scopedFedVarsForFunctionCall(String[] paramNames, List<Hop> argHops) {
		if (paramNames == null || paramNames.length == 0)
			return new ScopedFedVarOverride(java.util.Collections.emptyMap());

		final int n = (argHops == null) ? 0 : Math.min(paramNames.length, argHops.size());
		java.util.Map<String, FedVarState> argStates = new java.util.HashMap<>();
		for (int i = 0; i < n; i++) {
			String param = paramNames[i];
			if (param == null || param.isEmpty())
				continue;
			FedVarState state = deriveFedVarStateFromArgument(argHops.get(i));
			if (state != null)
				argStates.put(param, state);
		}

		java.util.Map<String, FedVarState> previous = new java.util.HashMap<>();
		for (String param : paramNames) {
			if (param == null || param.isEmpty())
				continue;
			previous.put(param, captureFedVarState(param));
		}

		for (String param : previous.keySet()) {
			removeFedAnchorKey(param);
			removeFedInitVar(param);
		}

		for (java.util.Map.Entry<String, FedVarState> e : argStates.entrySet()) {
			String param = e.getKey();
			FedVarState state = e.getValue();
			if (state == null || !state.fedInit)
				continue;
			registerFedInitVar(param, state.fType, state.signature);
			// If the argument had a stable non-VAR anchor key, keep it as well (best-effort).
			if (state.anchorKey != null && !state.anchorKey.isEmpty())
				registerFedAnchorKey(param, state.anchorKey);
		}

		return new ScopedFedVarOverride(previous);
	}

	public static void registerFedRmvarProtectedVar(String varName) {
		if (varName != null && !varName.isEmpty())
			FED_RMVAR_PROTECTED_VARS.add(varName);
	}

	public static String getFedAnchorKey(String varName) {
		return (varName == null) ? null : FED_ANCHOR_KEYS.get(varName);
	}

	/**
	 * Returns true iff the given variable name is known to have a concrete runtime
	 * federated source (fed-init var or non-VAR anchor key).
	 */
	public static boolean hasConcreteFederatedSourceVar(String varName) {
		return hasConcreteFederatedSourceVar(varName, new HashSet<>());
	}

	private static boolean hasConcreteFederatedSourceVar(String varName, Set<String> visited) {
		if (varName == null || varName.isEmpty())
			return false;
		// Avoid infinite loops on self-referential or cyclic VAR anchors.
		if (!visited.add(varName))
			return false;
		if (isFedInitVar(varName))
			return true;
		String anchorKey = getFedAnchorKey(varName);
		if (anchorKey == null || anchorKey.isEmpty())
			return false;
		// Concrete signature-based / address-based anchors are always considered concrete.
		if (!anchorKey.startsWith("VAR:"))
			return true;
		// VAR anchors can still be concrete if they ultimately resolve to a concrete anchor.
		// Example: X_samples anchored to VAR:X where X is a fed-init var.
		String ref = anchorKey.substring("VAR:".length());
		int pipeIx = ref.indexOf('|');
		if (pipeIx >= 0)
			ref = ref.substring(0, pipeIx);
		return hasConcreteFederatedSourceVar(ref, visited);
	}

	/**
	 * Checks whether a transient-read can be treated as runtime-federated based on
	 * its mapped sources.
	 * This intentionally rejects planner-only hints (e.g., inferred FType on local
	 * sources) and only accepts concrete runtime federation origins.
	 */
	public static boolean hasConcreteFederatedSourceForTransientRead(DataOp transientRead, List<Hop> sourceHops) {
		if (transientRead == null || transientRead.getOp() != Types.OpOpData.TRANSIENTREAD)
			return false;
		boolean hasMappedSources = sourceHops != null && !sourceHops.isEmpty();
		if (!hasMappedSources)
			return hasConcreteFederatedSourceVar(transientRead.getName());
		for (Hop source : sourceHops) {
			if (source == null)
				continue;
			if (source instanceof DataOp) {
				DataOp dataSource = (DataOp) source;
				if (dataSource.getOp() == Types.OpOpData.FEDERATED)
					return true;
				if (dataSource.getOp() == Types.OpOpData.TRANSIENTREAD
						&& hasConcreteFederatedSourceVar(dataSource.getName()))
					return true;
			}
		}
		return false;
	}

	public static String getUniqueFedInitVarName() {
		if (FED_INIT_SIGNATURES.isEmpty())
			return null;
		String signature = null;
		String varName = null;
		for (Entry<String, String> entry : FED_INIT_SIGNATURES.entrySet()) {
			String sig = entry.getValue();
			if (sig == null)
				continue;
			if (signature == null) {
				signature = sig;
				varName = entry.getKey();
			}
			else if (!signature.equals(sig)) {
				return null;
			}
		}
		return varName;
	}

	/**
	 * Best-effort count of distinct federated workers based on registered FED init signatures.
	 *
	 * <p>Signatures encode the address list as a semicolon-separated prefix up to the first '|'.</p>
	 *
	 * @return maximum number of worker addresses seen across known FED init variables (at least 1)
	 */
	public static int getMaxFedInitWorkers() {
		int max = 0;
		for (String signature : FED_INIT_SIGNATURES.values()) {
			max = Math.max(max, countWorkersInSignature(signature));
		}
		return Math.max(1, max);
	}

	private static int countWorkersInSignature(String signature) {
		if (signature == null || signature.isEmpty())
			return 0;
		int bar = signature.indexOf('|');
		String addrPart = (bar >= 0) ? signature.substring(0, bar) : signature;
		if (addrPart.isEmpty())
			return 0;
		int count = 0;
		for (String tok : addrPart.split(";")) {
			if (tok != null && !tok.isBlank())
				count++;
		}
		return count;
	}

	public static FType deriveFedInitFType(DataOp fedInit) {
		if (fedInit == null)
			return null;
		Hop ranges = fedInit.getInput(fedInit.getParameterIndex(DataExpression.FED_RANGES));
		boolean rowPartitioned = true;
		boolean colPartitioned = true;
		int numRanges = 0;
		for (int i = 0; i < ranges.getInput().size() / 2; i++) {
			numRanges++;
			Hop beg = ranges.getInput(2 * i);
			Hop end = ranges.getInput(2 * i + 1);
			long rl = HopRewriteUtils.getIntValueSafe(beg.getInput(0));
			long ru = HopRewriteUtils.getIntValueSafe(end.getInput(0));
			long cl = HopRewriteUtils.getIntValueSafe(beg.getInput(1));
			long cu = HopRewriteUtils.getIntValueSafe(end.getInput(1));
			rowPartitioned &= (cu - cl == fedInit.getDim2());
			colPartitioned &= (ru - rl == fedInit.getDim1());
		}
		if (rowPartitioned && colPartitioned) {
			// If every range spans the full matrix and multiple ranges exist, the mapping
			// is replicated (broadcast-like) rather than a single full partition.
			return (numRanges > 1) ? FType.BROADCAST : FType.FULL;
		}
		if (rowPartitioned)
			return FType.ROW;
		if (colPartitioned)
			return FType.COL;
		return FType.OTHER;
	}

	public static String deriveFedInitSignature(DataOp fedInit) {
		if (fedInit == null || fedInit.getOp() != org.apache.sysds.common.Types.OpOpData.FEDERATED)
			return null;
		int addrIx = fedInit.getParameterIndex(DataExpression.FED_ADDRESSES);
		int rangeIx = fedInit.getParameterIndex(DataExpression.FED_RANGES);
		if (addrIx < 0 || rangeIx < 0)
			return null;
		Hop addrHop = fedInit.getInput(addrIx);
		Hop rangeHop = fedInit.getInput(rangeIx);
		if (addrHop == null || rangeHop == null)
			return null;

		FType fType = deriveFedInitFType(fedInit);
		StringBuilder sb = new StringBuilder();
		if (addrHop.getInput().isEmpty())
			return null;
		for (Hop addr : addrHop.getInput()) {
			if (!(addr instanceof LiteralOp))
				return null;
			sb.append(((LiteralOp) addr).getStringValue()).append(';');
		}
		sb.append('|');

		List<Hop> ranges = rangeHop.getInput();
		if (fType != FType.FULL) {
			if (ranges == null || ranges.isEmpty() || ranges.size() % 2 != 0)
				return null;
			for (int i = 0; i < ranges.size(); i += 2) {
				Hop beg = ranges.get(i);
				Hop end = ranges.get(i + 1);
				Long rl = getLiteralLong(beg, 0);
				Long cl = getLiteralLong(beg, 1);
				Long ru = getLiteralLong(end, 0);
				Long cu = getLiteralLong(end, 1);
				if (rl == null || cl == null || ru == null || cu == null)
					return null;
				if (fType == FType.ROW)
					sb.append(rl).append(',').append(ru).append(';');
				else if (fType == FType.COL)
					sb.append(cl).append(',').append(cu).append(';');
				else
					sb.append(rl).append(',').append(cl).append(',')
						.append(ru).append(',').append(cu).append(';');
			}
		}
		return sb.toString();
	}

	public static String deriveFedMappingSignature(org.apache.sysds.runtime.controlprogram.federated.FederationMap fmap) {
		if (fmap == null)
			return null;
		List<Pair<FederatedRange, FederatedData>> entries = fmap.getMap();
		if (entries == null || entries.isEmpty())
			return null;

		FType fType = fmap.getType();
		StringBuilder sb = new StringBuilder();
		for (Pair<FederatedRange, FederatedData> entry : entries) {
			FederatedData data = entry.getValue();
			if (data == null || data.getAddress() == null)
				return null;
			sb.append(data.getAddress().toString()).append(';');
		}
		sb.append('|');
		if (fType != FType.FULL) {
			for (Pair<FederatedRange, FederatedData> entry : entries) {
				FederatedRange range = entry.getKey();
				if (range == null || range.getBeginDims().length < 2 || range.getEndDims().length < 2)
					return null;
				long[] beg = range.getBeginDims();
				long[] end = range.getEndDims();
				if (fType == FType.ROW)
					sb.append(beg[0]).append(',').append(end[0]).append(';');
				else if (fType == FType.COL)
					sb.append(beg[1]).append(',').append(end[1]).append(';');
				else
					sb.append(beg[0]).append(',').append(beg[1]).append(',')
						.append(end[0]).append(',').append(end[1]).append(';');
			}
		}
		return sb.toString();
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

	public static Privacy getPrivacyConstraint(Hop hop, List<Hop> inputHops, Map<Long, Privacy> privacyMap) {
		Privacy[] pc = new Privacy[inputHops.size()];
		StringBuilder missingPrivacy = new StringBuilder();
		for (int i = 0; i < inputHops.size(); i++) {
			Hop inputHop = inputHops.get(i);
			Privacy p = privacyMap.get(inputHop.getHopID());
			if (p == null) {
				if (missingPrivacy.length() > 0)
					missingPrivacy.append(", ");
				missingPrivacy.append(inputHop.getHopID()).append(" (").append(inputHop.getOpString()).append(")");
			}
			pc[i] = p;
		}

		if (missingPrivacy.length() > 0) {
			FederatedPlannerLogger.logWarnMessage(
					"Missing privacy entry for input hop(s): " + missingPrivacy +
							" while evaluating hop " + hop.getHopID() + " (" + hop.getOpString()
							+ "); treating as PUBLIC.");
		}

		boolean hasPrivateAggreate = false;

		for (Privacy p : pc) {
			if (p == Privacy.PRIVATE) {
				return Privacy.PRIVATE;
			} else if (p == Privacy.PRIVATE_AGGREGATE) {
				hasPrivateAggreate = true;
			}
		}

		if (hasPrivateAggreate) {
			if (hop instanceof AggUnaryOp) {
				AggUnaryOp au = (AggUnaryOp) hop;
				if (isPublicSafeFullAggregate(au))
					return Privacy.PUBLIC;
				return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
			} else if (hop instanceof AggBinaryOp || hop instanceof QuaternaryOp) {
				return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
			} else if (hop instanceof UnaryOp) {
				OpOp1 op = ((UnaryOp) hop).getOp();
				if (op == OpOp1.NROW || op == OpOp1.NCOL || op == OpOp1.LENGTH)
					return Privacy.PUBLIC;
				return Privacy.PRIVATE_AGGREGATE;
			} else if (hop instanceof TernaryOp) {
				switch (((TernaryOp) hop).getOp()) {
					case MOMENT:
					case COV:
					case CTABLE:
					case INTERQUANTILE:
					case QUANTILE:
						return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
					default:
						return Privacy.PRIVATE_AGGREGATE;
				}
			} else if (hop instanceof ParameterizedBuiltinOp) {
				ParamBuiltinOp op = ((ParameterizedBuiltinOp) hop).getOp();
				if (op == ParamBuiltinOp.GROUPEDAGG || op == ParamBuiltinOp.CONTAINS)
					return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
				return Privacy.PRIVATE_AGGREGATE;
			} else {
				return Privacy.PRIVATE_AGGREGATE;
			}
		}

		return Privacy.PUBLIC;
	}

	private static Privacy joinPrivacy(Privacy a, Privacy b) {
		if (a == null)
			return b;
		if (b == null)
			return a;
		if (a == b)
			return a;

		// Strongest privacy wins: PRIVATE > PRIVATE_AGGREGATE >
		// PRIVATE_AGGREGATE_TO_PUBLIC > PUBLIC
		if (a == Privacy.PRIVATE || b == Privacy.PRIVATE)
			return Privacy.PRIVATE;
		if (a == Privacy.PRIVATE_AGGREGATE || b == Privacy.PRIVATE_AGGREGATE)
			return Privacy.PRIVATE_AGGREGATE;
		if (a == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC || b == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC)
			return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
		return Privacy.PUBLIC;
	}

	private static Privacy mergePrivacyConstraint(Privacy current, String privacyConstraints) {
		Privacy parsed = parsePrivacyConstraint(privacyConstraints);
		return current == null ? parsed : joinPrivacy(current, parsed);
	}

	private static Privacy parsePrivacyConstraint(String privacyConstraints) {
		if (privacyConstraints == null)
			return null;
		String pcLower = privacyConstraints.trim().toLowerCase();
		if (pcLower.isEmpty())
			return null;
		if (pcLower.equals("private")
				|| pcLower.equals(Privacy.PRIVATE.toString().toLowerCase())) {
			return Privacy.PRIVATE;
		}
		if (pcLower.equals("private-aggregate") || pcLower.equals("private_aggregate")
				|| pcLower.equals(Privacy.PRIVATE_AGGREGATE.toString().toLowerCase())) {
			return Privacy.PRIVATE_AGGREGATE;
		}
		if (pcLower.equals("private-aggregate-to-public")
				|| pcLower.equals("private_aggregate_to_public")
				|| pcLower.equals(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC.toString().toLowerCase())) {
			return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
		}
		if (pcLower.equals("public")
				|| pcLower.equals(Privacy.PUBLIC.toString().toLowerCase())) {
			return Privacy.PUBLIC;
		}
		throw new DMLRuntimeException("Invalid privacy constraint: " + privacyConstraints
				+ ". Must be one of 'PRIVATE', 'PRIVATE_AGGREGATE', 'PRIVATE_AGGREGATE_TO_PUBLIC', 'PUBLIC'.");
	}

	private static String tryLocalPrivacyFallback(FedWorkerContext wctx) {
		if (wctx == null || wctx.filePath == null || !isLocalHost(wctx.host))
			return null;
		return readPrivacyConstraintsFromLocalMTD(wctx.filePath);
	}

	private static boolean isLocalHost(String host) {
		if (host == null)
			return false;
		if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host))
			return true;
		try {
			return InetAddress.getByName(host).isLoopbackAddress();
		} catch (Exception ex) {
			return false;
		}
	}

	private static String readPrivacyConstraintsFromLocalMTD(String filePath) {
		String mtdName = DataExpression.getMTDFileName(filePath);
		FileSystem fs = null;
		try {
			fs = IOUtilFunctions.getFileSystem(mtdName);
			Path path = new Path(mtdName);
			if (!fs.exists(path))
				return null;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(path)))) {
				MetaDataAll mtd = new MetaDataAll(br);
				if (!mtd.mtdExists())
					return null;
				return mtd.getPrivacyConstraints();
			}
		} catch (Exception ex) {
			return null;
		} finally {
			IOUtilFunctions.closeSilently(fs);
		}
	}

	private static boolean isPublicSafeFullAggregate(AggUnaryOp hop) {
		// Full aggregates here are considered safe to downgrade to PUBLIC
		// when inputs are PRIVATE_AGGREGATE, e.g., for min/max style scans.
		Types.Direction dir = hop.getDirection();

		if (dir == Types.Direction.RowCol) {
			switch (hop.getOp()) {
				case SUM:
				case SUM_SQ:
				case MIN:
				case MAX:
					return true;
				default:
					return false;
			}
		}

		// Axis aggregates that are considered safe to downgrade when inputs are
		// PRIVATE_AGGREGATE.
		if (dir == Types.Direction.Row || dir == Types.Direction.Col) {
			switch (hop.getOp()) {
				case MEAN:
				case SUM_SQ:
					return true;
				default:
					return false;
			}
		}

		return false;
	}

	private static class FedWorkerContext {
		final String host;
		final int port;
		final String filePath;
		final FederatedData data;

		FedWorkerContext(String host, int port, String filePath, FederatedData data) {
			this.host = host;
			this.port = port;
			this.filePath = filePath;
			this.data = data;
		}
	}
}

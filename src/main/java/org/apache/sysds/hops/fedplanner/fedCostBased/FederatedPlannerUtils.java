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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.common.Types;
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
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Utility class for federated planners.
 */
public class FederatedPlannerUtils {
	private static final String FED_MATRIX_IDENTIFIER = "matrix";

	/**
	 * Get transient inputs from either paramMap or transientWrites.
	 * Inputs from paramMap has higher priority than inputs from transientWrites.
	 * @param currentHop hop for which inputs are read from maps
	 * @param paramMap of local parameters
	 * @param transientWrites map of transient writes
	 * @param localVariableMap map of local variables
	 * @return inputs of currentHop
	 */
	public static ArrayList<Hop> getTransientInputs(Hop currentHop, Map<String, Hop> paramMap,
		Map<String,Hop> transientWrites, LocalVariableMap localVariableMap){
		Hop tWriteHop = null;
		if ( paramMap != null)
			tWriteHop = paramMap.get(currentHop.getName());
		if ( tWriteHop == null )
			tWriteHop = transientWrites.get(currentHop.getName());
		if ( tWriteHop == null ) {
			if(localVariableMap.get(currentHop.getName()) != null)
				return null;
			else
				throw new DMLRuntimeException("Transient write not found for " + currentHop);
		}
		else
			return new ArrayList<>(Collections.singletonList(tWriteHop));
	}

	/**
	 * Return parameter map containing the mapping from parameter name to input hop
	 * for all parameters of the function hop.
	 * @param funcOp hop for which the mapping of parameter names to input hops are made
	 * @return parameter map or empty map if function has no parameters
	 */
	public static Map<String,Hop> getParamMap(FunctionOp funcOp){
		String[] inputNames = funcOp.getInputVariableNames();
		Map<String,Hop> paramMap = new HashMap<>();
		if ( inputNames != null ){
			for ( int i = 0; i < funcOp.getInput().size(); i++ )
				paramMap.put(inputNames[i],funcOp.getInput(i));
		}
		return paramMap;
	}

	public static double computeForwardingWeightOfChild(double networkWeight,
		List<Pair<Long, Double>> parentLoopContext, List<Pair<Long, Double>> childLoopContext) {
		double base = (networkWeight != 0.0) ? networkWeight : 1.0;

		if (parentLoopContext == null || parentLoopContext.isEmpty())
			return base;

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

		return weight;
	}

	// NOTE: keep privacy semantics in sync with DP planner.
	public static Privacy getFedWorkerMetaData(
		List<Pair<FederatedRange, FederatedData>> fedMap, DataOp initFedOp) {
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
			? Types.DataType.MATRIX : Types.DataType.FRAME;

		// Local list for privacy calculation of this DataOp only
		List<FedWorkerContext> localWorkers = new ArrayList<>();

		// Init Fed Data
		for (int i = 0; i < addressList.size(); i++) {
			String address = addressList.get(i);
			// We split address into url/ip, the port and file path of file to read
			String[] parsedValues = InitFEDInstruction.parseURL(address);
			String host = parsedValues[0];
			int port = Integer.parseInt(parsedValues[1]);
			String filePath = parsedValues[2];

			long[] beginRange = rangeList.get(2 * i);
			long[] endRange = rangeList.get(2 * i + 1);

			try {
				FederatedData federatedData = new FederatedData(fedDataType,
					new InetSocketAddress(InetAddress.getByName(host), port), filePath);
				FederatedRange range = new FederatedRange(beginRange, endRange);
				Pair<FederatedRange, FederatedData> pair = new ImmutablePair<>(range, federatedData);

				fedMap.add(pair);      // Global worker count approximation
				localWorkers.add(new FedWorkerContext(host, port, filePath, federatedData));
			} catch (UnknownHostException e) {
				throw new DMLRuntimeException("federated host was unknown: " + host, e);
			}
		}
		Privacy privacyConstraint = null;
		boolean hadPrivacyFailure = false;

		// Request Privacy Constraints.
		// Privacy is derived only from this DataOp's workers (localWorkers).
		for (FedWorkerContext wctx : localWorkers) {
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
						String msg = "Worker " + wctx.host + ":" + wctx.port + " (" + wctx.filePath
							+ ") returned null privacy constraints for FEDERATED data op '" + initFedOp.getName()
							+ "' (hopID=" + initFedOp.getHopID() + ")";
						FederatedPlannerLogger.logErrorMessage("[FederatedPlanner] " + msg);
						hadPrivacyFailure = true;
						continue;
					}

					Privacy tempPrivacy = null;
					String pcLower = privacyConstraints.trim().toLowerCase();

					// Map to appropriate PrivacyConstraint value based on input string
					if (pcLower.equals("private")
							|| pcLower.equals(Privacy.PRIVATE.toString().toLowerCase())) {
						tempPrivacy = Privacy.PRIVATE;
					} else if (pcLower.equals("private-aggregate") || pcLower.equals("private_aggregate")
							|| pcLower.equals(Privacy.PRIVATE_AGGREGATE.toString().toLowerCase())) {
						tempPrivacy = Privacy.PRIVATE_AGGREGATE;
					} else if (pcLower.equals("private-aggregate-to-public")
							|| pcLower.equals("private_aggregate_to_public")
							|| pcLower.equals(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC.toString().toLowerCase())) {
						tempPrivacy = Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
					} else if (pcLower.equals("public")
							|| pcLower.equals(Privacy.PUBLIC.toString().toLowerCase())) {
						tempPrivacy = Privacy.PUBLIC;
					} else {
						throw new DMLRuntimeException("Invalid privacy constraint: " + privacyConstraints
								+ ". Must be one of 'PRIVATE', 'PRIVATE_AGGREGATE', 'PRIVATE_AGGREGATE_TO_PUBLIC', 'PUBLIC'.");
					}

					if (privacyConstraint == null) {
						privacyConstraint = tempPrivacy;
					} else {
						privacyConstraint = joinPrivacy(privacyConstraint, tempPrivacy);
					}
				} else {
					// Error handling: treat any unsuccessful response as fatal for planning
					String errorMsg = response.getErrorMessage();
					FederatedPlannerLogger.logErrorMessage(
						"Failed to request privacy constraints from " + wctx.host + ":" + wctx.port + " (" + wctx.filePath
							+ ") for FEDERATED data op '" + initFedOp.getName() + "' (hopID=" + initFedOp.getHopID()
							+ "): " + errorMsg);
					hadPrivacyFailure = true;
				}
			} catch (Exception e) {
				// Exception handling: also treated as fatal for planning
				String errorContext = "Failed to request privacy constraints from " + wctx.host + ":" + wctx.port + " ("
					+ wctx.filePath + ") for FEDERATED data op '" + initFedOp.getName() + "' (hopID="
					+ initFedOp.getHopID() + ")";
				FederatedPlannerLogger.logException(errorContext, e);
				hadPrivacyFailure = true;
			}
		}
		if (privacyConstraint == null || hadPrivacyFailure) {
			String errorMsg = "One or more federated workers failed to provide valid privacy constraints for FEDERATED data op '"
				+ initFedOp.getName() + "' (hopID=" + initFedOp.getHopID()
				+ "); cannot safely plan federated execution.";
			FederatedPlannerLogger.logErrorMessage("[FederatedPlanner] " + errorMsg + " Aborting planning.");
			throw new DMLRuntimeException(errorMsg);
		}
		return privacyConstraint;
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
				" while evaluating hop " + hop.getHopID() + " (" + hop.getOpString() + "); treating as PUBLIC.");
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
			if (hop instanceof AggUnaryOp || hop instanceof AggBinaryOp || hop instanceof QuaternaryOp) {
				return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
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
			} else if (hop instanceof ParameterizedBuiltinOp
					&& ((ParameterizedBuiltinOp) hop).getOp() == ParamBuiltinOp.GROUPEDAGG) {
				return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
			} else {
				return Privacy.PRIVATE_AGGREGATE;
			}
		}

		return Privacy.PUBLIC;
	}

	private static Privacy joinPrivacy(Privacy a, Privacy b) {
		if (a == null) return b;
		if (b == null) return a;
		if (a == b)    return a;

		// Strongest privacy wins: PRIVATE > PRIVATE_AGGREGATE > PRIVATE_AGGREGATE_TO_PUBLIC > PUBLIC
		if (a == Privacy.PRIVATE || b == Privacy.PRIVATE)
			return Privacy.PRIVATE;
		if (a == Privacy.PRIVATE_AGGREGATE || b == Privacy.PRIVATE_AGGREGATE)
			return Privacy.PRIVATE_AGGREGATE;
		if (a == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC || b == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC)
			return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
		return Privacy.PUBLIC;
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

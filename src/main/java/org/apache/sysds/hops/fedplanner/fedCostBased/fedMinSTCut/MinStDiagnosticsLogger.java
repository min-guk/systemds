/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/** Direct logger for graph-free exact MinST diagnostics records. */
public final class MinStDiagnosticsLogger {
	private static final Log LOG = LogFactory.getLog(MinStDiagnosticsLogger.class);

	private MinStDiagnosticsLogger() {
		// utility class
	}

	public static void log(MinStDiagnostics diagnostics) {
		Objects.requireNonNull(diagnostics, "diagnostics");
		if(!LOG.isDebugEnabled())
			return;
		LOG.debug(render(diagnostics));
	}

	private static String render(MinStDiagnostics diagnostics) {
		StringBuilder message = new StringBuilder("MinST diagnostics objectiveBits=")
			.append(diagnostics.selectedObjectiveBits())
			.append(" source=").append(diagnostics.sourcePartitionNodeIds());
		message.append(" summaries=").append(diagnostics.optimalSummariesInMemoOrder());
		message.append(" hops=").append(diagnostics.hopsInSortedIdOrder());
		return message.toString();
	}
}

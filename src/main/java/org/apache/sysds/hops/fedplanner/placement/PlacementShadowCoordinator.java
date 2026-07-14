/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.parser.DMLProgram;

/** Central observational shadow lifecycle around the existing planner. */
public final class PlacementShadowCoordinator {
	private static final Log LOG = LogFactory.getLog(PlacementShadowCoordinator.class);
	private PlacementShadowCoordinator() { }

	public static Session begin(DMLProgram program) {
		try { return new Session(new NeutralPlacementGraphBuilder().build(program), null); }
		catch(Throwable t) {
			LOG.warn("Neutral placement shadow analysis failed without affecting planner selection", t);
			return new Session(null, t);
		}
	}

	public static final class Session {
		private final NeutralPlacementGraph baseline;
		private final Throwable failure;
		private Session(NeutralPlacementGraph baseline, Throwable failure) { this.baseline = baseline; this.failure = failure; }
		public NeutralPlacementGraph graph() { return baseline; }
		public Throwable failure() { return failure; }
		public PlacementShadowComparator.Diff observe(DMLProgram program) {
			if(baseline == null) return null;
			try {
				PlacementShadowComparator.Diff diff = new PlacementShadowComparator().compare(
					baseline, new NeutralPlacementGraphBuilder().build(program));
				if(!diff.isEmpty()) LOG.debug("Neutral placement shadow observed normalized differences: " + diff);
				return diff;
			}
			catch(Throwable t) {
				LOG.warn("Neutral placement shadow comparison failed without affecting planner selection", t);
				return null;
			}
		}
	}
}

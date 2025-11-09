package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers;

import org.apache.sysds.hops.fedplanner.FTypes.FType;

/**
 * Result container for federated type handler operations.
 * Contains the determined FType and the reasoning behind the decision.
 */
public class HandlerResult {
    private final FType fType;
    private final String reason;

    public HandlerResult(FType fType, String reason) {
        this.fType = fType;
        this.reason = reason;
    }

    public FType getFType() {
        return fType;
    }

    public String getReason() {
        return reason;
    }

    public String getReasoning() {
        return reason;
    }

    public boolean isSupported() {
        return fType != null;
    }

    /**
     * Static factory method for creating null result with reason
     */
    public static HandlerResult unsupported(String reason) {
        return new HandlerResult(null, reason);
    }

    /**
     * Static factory method for creating supported result
     */
    public static HandlerResult supported(FType fType, String reason) {
        return new HandlerResult(fType, reason);
    }
}
package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.utils;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import java.util.Arrays;

/**
 * Logging utility for federated type determination.
 * Provides structured logging for debugging federated type analysis.
 */
public class FederatedTypeLogger {

    private static boolean debugEnabled = true; // Can be configured via system property

    /**
     * Enable or disable debug logging
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /**
     * Check if debug logging is enabled
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }


    /**
     * Logs a warning message for federated type analysis.
     *
     * @param message The warning message
     */
    public static void logWarning(String message) {
        System.err.println("[FEDERATED TYPE WARNING] " + message);
    }

    /**
     * Logs detailed handler selection information.
     *
     * @param hop The hop operation
     * @param handlerName The name of the selected handler
     */
    public static void logHandlerSelection(Hop hop, String handlerName) {
        if (!debugEnabled) {
            return;
        }

        System.out.println(String.format(
            "[Handler Selection] HopID: %d, Operation: %s, Handler: %s",
            hop.getHopID(),
            hop.getClass().getSimpleName(),
            handlerName
        ));
    }

    /**
     * Logs FType propagation details.
     *
     * @param operation Description of the propagation operation
     * @param inputs Input FTypes
     * @param result Resulting FType
     */
    public static void logPropagation(String operation, FType[] inputs, FType result) {
        if (!debugEnabled) {
            return;
        }

        System.out.println(String.format(
            "[FType Propagation] %s: %s → %s",
            operation,
            Arrays.toString(inputs),
            result
        ));
    }

    /**
     * Creates a formatted string representation of a Hop for logging.
     *
     * @param hop The hop to format
     * @return A formatted string representation
     */
    public static String formatHop(Hop hop) {
        return String.format("%s[%d]:%s",
            hop.getClass().getSimpleName(),
            hop.getHopID(),
            hop.getOpString()
        );
    }
}
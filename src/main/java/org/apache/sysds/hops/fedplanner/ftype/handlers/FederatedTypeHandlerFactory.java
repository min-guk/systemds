package org.apache.sysds.hops.fedplanner.ftype.handlers;

import org.apache.sysds.hops.Hop;
import java.util.Arrays;
import java.util.List;

/**
 * Factory class for selecting the appropriate FederatedTypeHandler for a given Hop operation.
 * Uses a chain of responsibility pattern to find the first handler that can process the operation.
 */
public class FederatedTypeHandlerFactory {

    private static final List<FederatedTypeHandler> handlers;

    static {
        // Initialize handlers in order of priority
        // More specific handlers should come before more general ones
        handlers = Arrays.asList(
            new OperationHandlers.NaryOpHandler(),
            new OperationHandlers.TernaryOpHandler(),
            new OperationHandlers.AggBinaryOpHandler(),
            new OperationHandlers.BinaryOpHandler(),
            new OperationHandlers.IndexingOpHandler(),
            new OperationHandlers.LeftIndexingOpHandler(),
            new OperationHandlers.UnaryOpHandler(),
            new OperationHandlers.QuaternaryOpHandler(),
            new OperationHandlers.AggUnaryOpHandler(),
            new OperationHandlers.ReorgOpHandler(),
            new OperationHandlers.ParameterizedBuiltinOpHandler(),
            new OperationHandlers.DefaultOpHandler() // Must be last - handles all remaining cases
        );
    }

    /**
     * Gets the appropriate handler for the given Hop operation.
     * Returns the first handler that can process the operation.
     *
     * @param hop The Hop operation to find a handler for
     * @return The appropriate FederatedTypeHandler (never null due to DefaultOpHandler)
     */
    public FederatedTypeHandler getHandler(Hop hop) {
        for (FederatedTypeHandler handler : handlers) {
            if (handler.canHandle(hop)) {
                return handler;
            }
        }
        // Should never reach here due to DefaultOpHandler, but included for safety
        return new OperationHandlers.DefaultOpHandler();
    }

    /**
     * Gets the list of all registered handlers (for testing/debugging)
     */
    public static List<FederatedTypeHandler> getAllHandlers() {
        return handlers;
    }
}
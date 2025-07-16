# Federated Operations Debugging Guide

This guide explains how to enable and use the federated operations debugging features in SystemDS.

## Overview

SystemDS now includes comprehensive logging for federated operations to help debug and trace execution flow. The logging system provides:

- Pre and post execution logging for all federated instructions
- Execution time tracking
- Detailed instruction information
- Error handling and reporting
- Configurable log levels

## Enabling Federated Debug Logging

### Method 1: Using the Debug Configuration File

Use the pre-configured debug settings:

```bash
systemds -config conf/SystemDS-config.xml -f script.dml -debug -log conf/log4j-fed-debug.properties
```

### Method 2: System Property

Enable federated debugging via system property:

```bash
systemds -Dsysds.federated.debug=true -f script.dml
```

### Method 3: Custom Log4j Configuration

Add these lines to your log4j.properties file:

```properties
# Enable federated instruction logging
log4j.logger.org.apache.sysds.runtime.instructions.fed=DEBUG

# Enable federated infrastructure logging
log4j.logger.org.apache.sysds.runtime.controlprogram.federated=DEBUG

# Enable federated planning logging
log4j.logger.org.apache.sysds.hops.fedplanner=DEBUG
```

## Log Output Format

### Federated Instruction Logs

Each federated instruction execution produces logs in this format:

```
[FED-START] InstructionClassName | Type: INSTRUCTION_TYPE | Opcode: opcode | Output: FOUT/LOUT/NONE | TID: threadId
[FED-END] InstructionClassName | Duration: Xms | Status: SUCCESS
```

Example:
```
[FED-START] BinaryMatrixScalarFEDInstruction | Type: Binary | Opcode: * | Output: NONE | TID: -1
[FED-END] BinaryMatrixScalarFEDInstruction | Duration: 45ms | Status: SUCCESS
```

### Error Logs

When errors occur:
```
[FED-ERROR] InstructionClassName | Duration: Xms | Error: error message
```

## Using Debug Methods in Code

For developers implementing new federated instructions, use the `debugFederatedState` method:

```java
// In your processInstruction method
debugFederatedState("Matrix operation",
    "rows", mo.getNumRows(),
    "cols", mo.getNumColumns(),
    "federated", mo.isFederated()
);
```

## Logging Levels

- **ERROR**: Only critical errors
- **WARN**: Warnings and errors
- **INFO**: High-level execution flow
- **DEBUG**: Detailed execution information (recommended for debugging)
- **TRACE**: Very detailed information including instruction strings

## Performance Considerations

Debug logging has minimal performance impact when disabled. The logging checks use:
- `if (LOG.isDebugEnabled())` guards to avoid string construction
- Lazy evaluation of log messages
- Efficient StringBuilder for message construction

## Troubleshooting Common Issues

### Issue: No federated logs appearing

**Solution**: Ensure you're using federated operations and that the input matrices are actually federated.

### Issue: Too much log output

**Solution**: Adjust the log level to INFO or WARN:
```properties
log4j.logger.org.apache.sysds.runtime.instructions.fed=INFO
```

### Issue: Missing execution times

**Solution**: Enable DEBUG level logging for FEDInstruction:
```properties
log4j.logger.org.apache.sysds.runtime.instructions.fed.FEDInstruction=DEBUG
```

## Integration with Federated Statistics

For comprehensive statistics, also enable:
```bash
systemds -stats -fedStats -f script.dml
```

This will provide additional performance metrics alongside the debug logs.

## Example Debug Session

Here's a complete example of debugging a federated matrix multiplication:

```bash
# Start federated workers
systemds WORKER 8001 &
systemds WORKER 8002 &

# Run script with debug logging
systemds -f federated_mm.dml -debug -log conf/log4j-fed-debug.properties -stats -fedStats

# View the logs
tail -f systemds-federated.log  # if file appender is enabled
```

## Additional Resources

- FederatedLoggingUtils.java - Utility methods for structured logging
- FEDInstruction.java - Base class with logging infrastructure
- log4j-fed-debug.properties - Pre-configured debug settings
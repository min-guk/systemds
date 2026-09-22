# State and runtime-boundary evidence for the independent plan oracle

This slice covers literal, direct symbol-table and function-boundary tuples. It is
implemented in `StateCapabilityOracle` and exercised by
`StateCapabilityOracleTest`. The oracle does not import placement rules or a
planner-produced candidate set. A `SUPPORTED` verdict proves only the stated
tuple's direct binding; it does **not** certify an entire physical plan.

| Coverage family | Independent observation | Bounded assertion | Remaining gap |
| --- | --- | --- | --- |
| `TransientReadRule` | `DataOp.java:273-277` constructs a transient read; `Recompiler.java:606-665` reads current runtime mapping and distinguishes a currently local value from a federated one. | A direct read with a matching reaching value version accepts local `CP/LOUT` and federated `FED/FOUT`; a stale selected version is rejected. | Dominance across branches, changing mappings, privacy, and transfer actions remain `UNKNOWN`. |
| `TransientWriteRule` | `DataOp.java:293-297` wires a write input; `VariableCPInstruction.java:315-323` identifies the symbol-table destination; `Dag.java:1351-1395` shows that FOUT materialization is an explicit lowering action. | Direct matching `CP/LOUT` local and `FED/FOUT` federated values are supported; an unmodeled conversion is `UNKNOWN`. | No proof of CP-to-FOUT upload, derived geometry, or all write alias effects. |
| `FunctionOutputRule` | `DataOp.java:286-293` constructs a function-output node; `FunctionCallCPInstruction.java:237-256` binds each returned value into the caller's symbol table. | One direct return's observed local or federated value supports the corresponding boundary tuple. | Multi-return fan-out and materialized return values require a separate proof. |
| `FunctionCallRule` | `FunctionCallCPInstruction.java:196-203,222-256` creates the function execution context and binds returns positionally. | A single observed output with matching formal/bound arity and valid output index is supported; a mismatched arity or invalid index is rejected. | Multi-output aliasing, recursive calls, dynamic control flow, and unknown return values remain `UNKNOWN`. |
| `VariableWriteRule` | `VariableCPInstruction.java:315-323,794-810` distinguishes a symbol-table destination from the three-operand file move and writes the destination binding. | An observed in-memory value assigned to a destination is supported. A file move is rejected **as a symbol-table publication**, not as an executable instruction. | Value changes from casts, copies, and unobserved runtime values remain `UNKNOWN`. |
| Recompile `CP/FOUT` | `FederatedRefedPolicy.java:635-647,4200-4208` rejects a selected runtime `TWrite` materialization with `CP/FOUT`. `Recompiler.java:500-536` only applies this contract when compiled federated planning is active. | The selected-plan recompile boundary rejects `CP/FOUT`; unknown context stays `UNKNOWN`. | This is a scoped selected-plan prohibition, **not** an independent claim that every possible CP/FOUT runtime program is invalid. Other noncanonical pairs stay `UNKNOWN` until lowering is proven. |

The fixture versions and mapping identity are supplied independently. They are
not derived from candidate receipts. A concrete federated mapping identity is
required for every federated `Value`. The test asserts positive and negative
tuples for all five rule-family mappings plus the recompile prohibition; it
does not yet populate every workload's runtime state. Therefore this slice
cannot change the whole-corpus certification verdict from `UNKNOWN` to `PASS`.

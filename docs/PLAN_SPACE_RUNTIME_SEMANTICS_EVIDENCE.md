# Independent FED runtime capability slice

`RuntimeCapabilityOracle` is a test-only, hand-grounded decision table. It reads
neither `RulesCore` nor `Rulesets`. A `SUPPORTED` verdict means the specified FED
runtime branch is reachable for a well-typed, shape-valid operation with public
data. It does **not** establish end-to-end plan feasibility, numerical correctness,
placement authority, or privacy legality. The full certificate remains `UNKNOWN`
until those constraints are established independently and the table is connected
to complete P/E plan identities.

The current table recognizes three non-variance instruction families and one
explicit variance rejection. Its direct evidence is:

| Tuple or branch | Runtime source | Oracle effect |
|---|---|---|
| Matrix–scalar, local matrix | `BinaryMatrixScalarFEDInstruction.java:58-65` | Reject FED execution |
| Matrix–scalar, mapped matrix and scalar; forced local or federated output | `BinaryMatrixScalarFEDInstruction.java:67-112` | Reachable branch; local result or copied input FType |
| Matrix–matrix, both inputs local | `BinaryMatrixMatrixFEDInstruction.java:64-75` | Reject FED execution |
| Matrix–matrix, single-partition FULL inputs on one worker pool | `BinaryMatrixMatrixFEDInstruction.java:94-101,219-249` | Reachable branch; FULL or local output |
| Matrix–matrix, multi-partition FULL left and local right | `BinaryMatrixMatrixFEDInstruction.java:164-177` | Reject this FED path |
| Aggregate unary, local matrix | `AggregateUnaryFEDInstruction.java:119-127` | Reject FED execution |
| Aggregate unary, scalar forced FOUT | `AggregateUnaryFEDInstruction.java:153-156` | Reject FOUT |
| Aggregate unary, ROW/COL compatible orientation | `AggregateUnaryFEDInstruction.java:208-262` | Reachable FOUT branch preserving ROW/COL |
| Aggregate unary, ROW/COL requiring global consolidation | `AggregateUnaryFEDInstruction.java:160-191` | Reachable FOUT branch with BROADCAST mapping |
| Aggregate unary, local output | `AggregateUnaryFEDInstruction.java:302-312` | Reachable local output branch |
| Variance, forced FOUT | `AggregateUnaryFEDInstruction.java:315-319` | Reject FOUT |

Input FType distinctions follow `FTypes.java:53-94`; the decision table names
FTypes independently, without calling `FType.isType`, whose FULL/BROADCAST
membership is broader than an exact FType comparison. Unknown FTypes, worker
alignment, broadcast slicing, unmodeled output shape, and all other operation
families yield `UNKNOWN`. For matrix–matrix, a differing worker pool yields
`UNKNOWN` rather than a blanket rejection because a separate broadcast path may
apply. All restricted or unspecified privacy tuples remain `UNKNOWN` for positive
capability claims; a runtime exception can still prove a structurally rejected
tuple. The worker handles `GET_VAR` through
`FederatedWorkerHandler.java:375-378`, but that dispatch alone does not prove a
given privacy label permits retrieval. No private-data download is certified here.

Tests exercise literal positive/negative tuples, a mismatched worker-pool mutant,
output-orientation mutants, privacy mutants, and unknown-family handling. The
remaining gap is substantial: the other registered rule families, shape and
opcode legality, output dimensions, the privacy propagation chain, transfer
actions, worker maps, and global plan interactions require separate evidence.

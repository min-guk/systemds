# Matrix-family runtime capability evidence

`MatrixCapabilityOracle` is a finite, independent reading of FED instruction
branches. It deliberately does **not** import planner rules or assert that a
whole physical plan is feasible. `SUPPORTED` means the stated public-data
tuple reaches a runtime branch with the stated materialization. `REJECTED`
means an explicit runtime guard, missing FED branch, incompatible matrix
dimensions, or an instruction that always publishes a different output form.
Everything else is `UNKNOWN`. In particular, privacy permissions, source
transfers, worker data availability, address co-location, and remote execution
success are outside this proof.

| Rule-family mapping | Runtime evidence | Explicit positive tuples | Explicit negative tuples | Open cases |
| --- | --- | --- | --- | --- |
| `MMFedRule` | `MMFEDInstruction.java` process branches around lines 84–164 | ROW × local matrix, FOUT→ROW and LOUT→local | local × local; dimensions mismatch | COL/ROW alignment, PART partial aggregates, sliced broadcast, other layouts |
| `BinaryMMRule` | `AggregateBinaryFEDInstruction.java` guards and branches around lines 116–336 | ROW × local, FOUT→ROW or LOUT→local; local × single-range FULL, FOUT→FULL | local × local; dimensions mismatch | worker-pool alignment, co-located COL/ROW, broadcast, PART, local-backed mapping |
| `MMChainRule` | `MMChainFEDInstruction.java` lines 54–65, 97–149 | ROW or one-range FULL with local vector, unweighted, LOUT→local | COL or multi-range FULL main input; FOUT | weighted input alignment and third-input shape |
| `TsmmRule` | `TsmmFEDInstruction.java` lines 55–60, 84–128 | ROW/LEFT and COL/RIGHT; FOUT→BROADCAST, LOUT→local | local input or wrong axis/direction | special map shapes and remote failures |
| `SolveRule` | No `SolveFEDInstruction` or solve dispatch under `runtime/instructions/fed` found | none | none | All solve FED cases remain UNKNOWN; CP solve needs separate evidence |
| `ReorgUnaryRule` (transpose only) | `ReorgFEDInstruction.java` lines 171–221 | ROW→COL FOUT; BROADCAST→local LOUT | local, PART, OTHER input | REV, ROLL, DIAG, range geometry and Spark path |
| `ReshapeRule` | `ReshapeFEDInstruction.java` lines 92–168 | one-range FULL 2×6→3×4, by-row, FOUT→ROW | local input, unequal cells, nondivisible worker range, LOUT requested | multi-range range placement, inferred dimensions, by-column geometry |

The tuple suite is `MatrixCapabilityOracleTest`. The named rule families are
coverage keys only; the oracle's decisions come from the FED runtime files.
The test inputs are intentionally small so each branch assertion is readable.
These tuples do not establish exhaustiveness over the listed families or over
any workload. In a full certification, an `UNKNOWN` tuple must prevent PASS
until resolved by a separate machine-checkable proof or runtime execution.

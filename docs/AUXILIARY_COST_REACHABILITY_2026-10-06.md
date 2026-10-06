# Auxiliary federated stages: runtime and planner reachability

This note records the request batches observed directly from the federated
instruction implementations and separates them from paths that the production
planner can actually select. `AuxiliaryStageBatchContractTest` executes the
real instruction request construction against the existing mock transport; it
does not run a public workload or contact a worker.

## Runtime request ledger

| Instruction path | Batches beyond the main worker kernel | Payload and coordinator work |
| --- | ---: | --- |
| Ordinary CTABLE, LOUT | 1 dimension batch | Worker `uamax`, `GET_VAR`, cleanup; coordinator takes the maximum scalar dimension. The main batch slices and broadcasts the local input, executes CTABLE, gets the partial result, and aggregates it locally. |
| Ordinary CTABLE, FOUT | 2 batches | The dimension batch above plus one post-kernel `EXEC_UDF(SliceOutput)` request per worker. The UDF crops each partial output; it is absent from the main CTABLE request batch. |
| CTABLE with two federated matrix inputs | Cold: 3 auxiliary batches; warm: 2, plus the main kernel | Each federated dimension is discovered independently. If the secondary matrix is not already bound locally, the coordinator collects it, then `broadcastSliced` redistributes it using the primary map before the main kernel. |
| `ctableexpand` | No post-output slice UDF | It follows the dimension/main execution path but bypasses ordinary CTABLE `SliceOutput` handling. |
| Reshape, FOUT | 1 metadata batch | A separate schema-only `PUT_VAR` precedes the main reshape execution. It carries data characteristics and data type, not a matrix block; the observed serialization buffer estimate is 512 bytes. |
| TSMM, forced FOUT runtime branch, cold source | 1 collect stage plus main broadcast/execute | `acquireRead` materializes the complete source on the coordinator, then the full matrix is broadcast to every worker before TSMM executes. |
| TSMM, forced FOUT runtime branch, cached source | Main broadcast/execute only | A previously cached local matrix skips the source `GET_VAR`, but the complete matrix is still broadcast to every worker. |

The CTABLE batches are visible in `CtableFEDInstruction`: dimension discovery
uses worker execution plus `GET_VAR`, ordinary local output adds a result
`GET_VAR`, and native FOUT performs the later `SliceOutput` UDF. Reshape sends
its schema `PUT_VAR` separately before the worker instruction. TSMM's forced
FOUT branch broadcasts the coordinator-side matrix rather than preserving a
partitioned input execution.

## Production reachability

### CTABLE native FOUT

The SliceLine analysis publishes both native FED/FOUT and FED/LOUT candidate
emissions for ordinary CTABLE. Candidate availability alone is insufficient
for costing: the resulting `ExactPhysicalModel` CTABLE domain contains five
captured FED/LOUT alternatives and no native FOUT alternative. Pinning and
solving a native FOUT value is therefore impossible because there is no such
Exact value. A second parsed two-federated-input CTABLE probe also produced no
selectable native FOUT Exact domain.

Consequently, no CTABLE FOUT post-UDF charge was added to
`ExactPhysicalCostModel`. Adding a dormant predicate using an unrelated native
FOUT alternative would not prove a hard-factor-feasible CTABLE lowering. The
runtime batch contract remains covered so that a future planner change can add
the cost together with a real selectable realization test.

The dimension-discovery cost is output-independent and applies to currently
selectable ordinary CTABLE paths. `ctableexpand` remains excluded from the
ordinary local dimension scan when its sequence-marker input supplies that
dimension.

### Forced FOUT TSMM

`Rulesets.BinaryMMRule.tsmmCaps` always returns a federated-local capability
with `FOUT_NOT_SUPPORTED_BY_RUNTIME`. Fresh and manually pre-marked FOUT TSMM
Hops therefore expose the same production choices: native FED/LOUT and a
derived FED/FOUT materialization whose execution type remains FULL. No native
forced-FOUT TSMM alternative is selectable, so the cold `GET_VAR` and full
broadcast runtime branch must not be charged to normal TSMM or to derived FOUT.
The derived path is already represented as LOUT execution followed by the
explicit upload action.

### Two-federated-input CTABLE

Aligned ordinary two-FED CTABLE is reachable as native FED/LOUT: a complete
hard+cost solve selects DIRECT_FOUT ROW inputs backed by FED/FOUT ROW producers,
and projection/emission lowers to `FED°ctable…°LOUT`, parsed as
`CtableFEDInstruction`. The same evidence exists with native-local matrix weights.
The cross-pool direct-direct alternatives fail complete hard-factor solving;
the inspected fixtures expose no CTABLE relocation or native FOUT alternative.

The follow-up fix adds a runtime collection demand without reclassifying the
input authority. The existing GET materialization collector owns the cold read,
coalesces only proven retained MatrixObject aliases/creation scopes, and keeps
runtime-relocated MatrixObjects separate. A secondary sliced PUT remains a
per-invocation auxiliary cost. Identical maps do not prove identical objects:
the runtime tests observe another GET for a distinct MatrixObject, but none
for a second ExecutionContext name referring to the same object.

The runtime ledger also covers local, aligned remote and nonaligned remote
weights. Fully aligned remote weights are reused by ID; other weights are
broadcast per call. Nonaligned remote weights have a cold GET under the same
materialization ownership rules. Ordinary `isFedOutput` slices and scans the
secondary even when output is forced LOUT. This local work is charged separately
from dimension discovery. Singleton/FULL broadcast does not copy PUT slices;
secondary dimension batches use the secondary map's response count.

The assertion-bearing reachability probe and DML fixtures are in
`/home/mchoi/cost-followup-20261006/ctable-followup/reachability/`.
`ExactCtableMaterializationCostTest` locks complete-assignment and lowering
contracts; `CtableInputPreparationCostTest` and
`ExactAuxiliaryNativeInputUploadTest` lock quantities and upload ownership.

## Residual runtime observations

- Ordinary non-reversed CTABLE FOUT adjusts output ranges toward a column
  layout but retains the primary map's ROW type. The checked fixtures expose no
  selectable exact realization relying on this state; this is not a universal
  impossibility proof.
- A weights-only federated CTABLE form appears able to reach a null primary-map
  dereference before the reversed-weights path. This is a separate runtime
  correctness issue and is not modeled as a cost.

## Verification

The isolated Java/JUnit run against the existing compiled repository classes
passed `AuxiliaryStageBatchContractTest`, `NativeResultBatchContractTest`,
`CtableFEDInstructionOutputContractTest`, and
`ReshapeFEDInstructionNoFallbackTest` (`OK (24 tests)`). The reachability probe
confirmed that the SliceLine PRIVATE_AGGREGATE CTABLE Exact domain has five
alternatives and none is native FOUT; the same SliceLine program under strict
PRIVATE privacy closes earlier because another protected cumulative operation
has no legal physical placement.

Probe sources are preserved under `/home/mchoi/cost-followup-20261006/aux-runtime/` (`ReachabilityProbe.java`, `CtableExactReachabilityProbe.java`). The integrated final build/test results are recorded in [the follow-up report](COST_MODEL_FOLLOWUP_2026-10-06.md).

The integrated CTABLE follow-up passes all 17 new regressions, with 463/464
combined cost/ownership tests passing; the sole failure is the previously
reproduced StepLM factor-cell overflow. Docker DP compile/lowering passes 28/28.
See [the final CTABLE receipt](experiments/cost-followup-20261006/ctable-validation.json).

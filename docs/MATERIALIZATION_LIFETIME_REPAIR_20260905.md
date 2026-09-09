# Materialization lifetime repair audit (2026-09-05)

## Decision

**Rejected: do not implement a `Dag` hoist or frequency-cost repair.**

The proposed repair assumed that a planner-synthetic `prefetch` placed inside an iterative statement block downloads the same immutable federated input on every iteration. Authenticated baseline instruction timings falsify that assumption. The instruction executes repeatedly, but the input `MatrixObject` is materialized remotely only on its first acquire; later executions are cache hits. This matches the cost model's one-download contract.

No production source or regression test was changed. In particular, `Dag.java`, `FederatedCostModel.java`, `ExactPhysicalCostModel.java`, and `ExactCategoricalSolver.java` are untouched.

## Repair plan and falsification gate

The candidate plan was:

1. Prove that repeated loop-local prefetch executions issue repeated remote `GET_VAR` data transfers for the same value version.
2. Prove that the input value is immutable and live at a legal function/loop dominator across recompilation.
3. Add a RED regression that counts remote acquisitions rather than instruction executions.
4. Only then hoist materialization to that dominator, preserving function scope, privacy metadata, value versions, and registry/recompile authority.

Step 1 failed. Therefore steps 3-4 would test and modify a nonexistent defect. A test asserting dynamic prefetch count `==1` would also encode the wrong contract: repeated lightweight instructions are permitted, while repeated remote reads are not.

## Runtime proof: one remote acquire, then cache hits

### Source contract

* Planner-synthetic prefetch calls `acquireReadAndRelease()` on the input and publishes the returned block as a local output: `src/main/java/org/apache/sysds/runtime/instructions/cp/PrefetchCPInstruction.java:46-71`.
* `acquireReadIntern()` reaches `readBlobFromFederated()` only while `_data == null`: `src/main/java/org/apache/sysds/runtime/controlprogram/caching/CacheableData.java:571-612`.
* The local output clears its federation map and stores the returned `MatrixBlock`: `src/main/java/org/apache/sysds/runtime/controlprogram/context/ExecutionContext.java:644-652`.
* `acquireModify()` assigns that block reference; it does not copy the matrix: `src/main/java/org/apache/sysds/runtime/controlprogram/caching/CacheableData.java:699-717`.
* Instruction statistics attach each acquire duration to its dynamic instruction execution: `src/main/java/org/apache/sysds/utils/stats/InstructionStatistics.java:88-112`.

These contracts are present in both baseline commits used by the affected cells (`1785d1f3528b615244519e784242e65d164bd9eb` for LM and `9aefc6473957495846d45395dae03251fd9ea153` for LogReg/L2SVM).

### Archived timing distributions

Evidence resides under `/home/mchoi/g014-runtime-4net-w1357-20260901-control/baseline-instruction-evidence-20260905/<token>/`.

* LM Heuristic LAN w1, token `e820d55dd3990acd1d8c`: 45 `prefetch X` executions; total acquire 53.757457891 s; first acquire 53.757343610 s; remaining 44 total 0.114281 ms, maximum 10.810 us, median 1.573 us. Exactly one exceeds 10 ms.
* LM Heuristic LAN w3, token `e0fbe318c8a2a1075753`: 45 executions; first 16.067823257 s; remaining total 0.109326 ms, maximum 16.070 us, median 1.233 us. Exactly one exceeds 10 ms.
* LM Exact WAN-Mid w3, token `41ceb960bfb21fe6e6ce`: first 17.500760310 s; remaining 44 total 0.114505 ms.
* LM Exact WAN-Mid w7, token `f55652f486a6443560ac`: first 22.347596920 s; remaining 44 total 0.126837 ms.
* LogReg Heuristic LAN w3, token `5236a162a2544a933e5d`: 137 `prefetch X` executions; first 17.125404578 s; remaining 136 total 0.284285 ms, median 1.383 us. Exactly one exceeds 10 ms.
* LogReg Exact WAN-Mid w7, token `b18804dd86a7e7aa009c`: 137 `prefetch X` executions; first 19.853985765 s; remaining 136 total 0.182985 ms, median 1.207 us. Exactly one exceeds 10 ms.
* L2SVM Heuristic LAN w3, token `025024ae31f8404b00e9`: 600 `prefetch Y` executions; first 18.935227 ms; remaining 599 total 1.118458 ms, median 1.493 us. Exactly one exceeds 10 ms.

The comprehensive endpoint table is in `/home/mchoi/g014-runtime-4net-w1357-20260901-control/REMAINING_ML_ANOMALY_DIAGNOSIS_20260905.md`.

Dynamic instruction counts are not RPC counts. The coordinator's aggregate `Federated I/O Get` also includes other FED instructions and cannot be mapped one-for-one to the prefetch rows. The planner-synthetic synchronous path reports `Federated prefetch count: 0`, so that statistic does not count these downloads either. Per-instruction acquire latency is the discriminating evidence here.

## Binding lifetime, loops, calls, and recompilation

The second gate confirms why the cached input survives:

1. Function-call binding puts the caller's `Data` object directly into the callee variable map; it does not clone the `MatrixObject`: `src/main/java/org/apache/sysds/runtime/instructions/cp/FunctionCallCPInstruction.java:144-175`.
2. Caller inputs are pinned before the function executes and unpinned after callee cleanup: `FunctionCallCPInstruction.java:191-209,220-234`.
3. Pinning disables cleanup on that same `CacheableData`: `src/main/java/org/apache/sysds/runtime/controlprogram/context/ExecutionContext.java:805-852`. `clearData()` returns immediately while cleanup is disabled: `CacheableData.java:815-830`.
4. A while loop optionally recompiles once before iteration and executes all children against the same `ExecutionContext`: `src/main/java/org/apache/sysds/runtime/controlprogram/WhileProgramBlock.java:98-129`. Recompilation does not replace the live function-input object.
5. `Dag` lowers the selected action within the current statement block and rewires consumer edges: `src/main/java/org/apache/sysds/lops/compile/Dag.java:330-474`. Consequently its instruction can recur, but each recurrence reads the already materialized input cache.

The output temporary may be removed/recreated per iteration, but its source is the still-live cached input. Recreating the local output envelope does not recreate the remote download.

## Cost/runtime contract

`computeReusableMaterializationDownloadCost()` explicitly describes and charges one reusable parallel `GET_VAR` batch: `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java:2043-2082`. The first-acquire distributions satisfy that cardinality.

The Exact WAN-Mid anomalies are variations in the one realized first-fetch duration:

* LM w5 -> w7: first `X` acquire 12.720718129 -> 22.347596920 s (+9.627 s); total runtime 16.914 -> 26.693 s (+9.779 s).
* LogReg w5 -> w7: first `X` acquire 12.442788688 -> 19.853985765 s (+7.411 s); total runtime 35.731 -> 42.138 s (+6.407 s).

This localizes the runtime rise but does not prove model mispricing. The baseline archives do not contain the selected action's exact memory estimate/objective trace, and each endpoint has one runtime sample. The evidence is compatible with calibration error, worker/Netty/serialization tail variation, or contention; it cannot distinguish them. A cost change is therefore no more justified than a hoist.

## Why the candidate hoist is unsafe and ineffective

A cross-statement-block hoist would need new proof for:

* the nearest legal dominator across function call boundaries;
* immutable value-version identity across loop-carried assignments and dynamic recompilation;
* privacy constraints and lineage identity at the new boundary;
* temporary cleanup and caller/callee ownership;
* registry authority scoped to copied/recompiled statement blocks.

Even if all were solved, the measured remote-transfer cardinality would remain one. The only saved work would be tens or hundreds of microsecond-scale cache-hit instructions, while the anomaly is seconds. This violates the minimal-fix requirement.

## Regression disposition and next evidence

No RED test exists for the alleged repeated-download defect because the archived executions are already GREEN for the intended one-download behavior. No Maven target was run, avoiding shared-target collision with concurrent work.

If a future change touches this contract, the correct narrow regression is a request-counting federated test that executes the same planner-synthetic prefetch repeatedly against one live immutable `MatrixObject` and asserts one remote data request while allowing multiple instruction executions. That test should also cover a changed value version and require a new request. It is not required to justify a current source change.

To investigate calibration separately, archive the selected materialization action's exact memory estimate and modeled components, then repeat uncontended cells and compare those one-fetch predictions against first-acquire distributions. Until then, retain the LM/LogReg Exact rises as unresolved model/network calibration or tail behavior and do not force cross-planner ordering.

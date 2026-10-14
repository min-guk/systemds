# MemoProgramFacts frozen lookup reuse

## Problem

`MemoProgramFacts` is frozen after its private `Builder.build()` finishes, but three read paths rebuilt
immutable results on every query:

- `guardPhases(loopId)` filtered the entire `guardPhases` list.
- `guardValue(loopId, guardId, phase)` filtered the same list and used `findFirst`.
- `guardDecision(guardId, callFrame)` allocated a new canonical decision even when the exact syntactic
  guard and invocation frame had already been resolved.
- `occurrenceContexts(occurrenceId)` rebuilt call-frame, ancestor-loop, execution-case, and phase lists.

The builder can replace `GuardPhase` entries while analysis is active. The optimization therefore starts
only in the `MemoProgramFacts` constructor after `guardPhases = List.copyOf(b.guardPhases)`. No index reads
builder-owned mutable collections.

## Implementation

- `MemoProgramFacts.java`
  - Builds an immutable per-loop list index in original registration order.
  - Builds a compact sorted guard-id/phase array for each loop. `putIfAbsent` while freezing preserves the
    former stream `findFirst` rule, and binary search avoids lookup allocation. Storage is linear in frozen
    guard-phase facts instead of `loopCount * guardCount`. Invalid or absent pairs still return `null`.
  - Replaces separate provenance/decision creation with one model-local, thread-safe cache per syntactic
    guard. The key is the exact call-frame list. A hit returns the same `GuardDecision`; canonical equality
    across different syntactic frames does not merge their stored `guardId` or `callFrame`.
  - Memoizes the immutable `occurrenceContexts` list by validated occurrence id. This cache has at most one
    entry per frozen occurrence and does not materialize phase Cartesian products.
- `MemoProgramFactsIndexTest.java`
  - Locks registration order, first-fact lookup, `null`/empty lookup behavior, alias equality, inversion,
    distinct frame identity, exact-query object reuse, and all-occurrence context equality/reuse.

No API was added for `MemoShapes`; its existing `guardValue` and `guardDecision` calls receive the indexed
and cached behavior transparently.

## Validation

Frozen baseline classpath:
`experiments/oracle-request-dedup-20261014/after-engine/{classes,test-classes,lib/*}`.

- Before production edit: `MemoProgramFactsIndexTest` passed 3 tests. Command and output are in
  `facts-before-command.txt`, `facts-before-javac.log`, and `facts-before-junit.log`.
- The recorded isolated production compilation in `facts-after-production-javac.log` is **not green for
  the final sparse-index source**: it caught a missing `java.util.Arrays` import at the binary-search call.
  The import is now present, and the leader subsequently reported an integrated Maven `test-compile` pass
  in 37.678 seconds. This lane did not rerun the isolated compiler after that coordinated fix.
- `MemoProgramFactsIndexTest`, `MemoProgramFactsTest`, and `MemoGuardProvenanceTest` passed 35 tests in
  `facts-after-junit.log`, but that run used the preceding compiled revision rather than the final sparse
  `GuardPhaseIndex`. It supports the canonical-guard and occurrence-context behavior; it must not be cited
  as execution evidence for the final sparse binary-search implementation.
- `git diff --check` passed for the owned production and test files.

The first baseline launch omitted `--add-modules jdk.incubator.vector`; two parser fixtures failed during
`UtilFunctions` class loading before assertions. That infrastructure-only result is retained in
`facts-before-junit-missing-vector-module.log`. Repeating the identical frozen classpath with the required
module produced the 3-test green reference above.

## Remaining risk

- The canonical guard cache retains the pre-existing lazy-cache cardinality: one entry for each exact
  `(syntactic guard, call frame)` queried on a model. Planner-generated frames are finite, but the public
  method does not impose a separate hard cap on arbitrary external frame lists.
- Per-loop lookup relies only on the existing zero-based loop-id invariant used by `loop(id)`. Guard ids are
  stored sparsely, so a loop with one high-numbered guard does not allocate all earlier guard slots. The
  regression suite exercises nested loops, function frames, aliases, and inversion.
- Full Maven compilation and the complete Memo suite are intentionally left to the leader's coordinated
  build. This lane used isolated output directories and did not modify `target/`.

## Artifact hashes

```text
488067cffbd66044b03d925d7237f02c1d4acec94203918cc7c25c1177315a48  MemoProgramFacts.java (historical tested revision)
bd9c25bcc864d7c7bb75368241dd29390ec567ba3647649d9900fa82df127500  MemoProgramFactsIndexTest.java
e7df958a24e1566db720cad9600efda0bbeb8284f270ff150528b3e9d00e01a0  facts-before-junit.log
4a4e87be33b2abacd94238dc31c8834b57f016c2bbefd517e9bd12c1029f3450  facts-after-junit.log
```

The current source hash after the sparse index and import fix is
`091b152d1adb3726c3c7d29ec3399a815c9d0a629cad5fe8e2aee9a599fd8b56`; its authoritative validation is
the leader's integrated build and final suite, not the historical isolated log above.

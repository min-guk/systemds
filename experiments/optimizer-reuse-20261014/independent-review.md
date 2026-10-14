# Optimizer reuse independent review

## Verdict

No material correctness defect was found in the reviewed `MemoProgramFacts`, `MemoOptimizer`, or
`MemoShapes` reuse changes. The Shapes proof caches preserve the full semantic inputs used by the shape
walk, publish only completed results, and retain `UNRESOLVED` outcomes. The remaining points below are
coverage and lifecycle limitations rather than must-fix defects.

After this source review, the leader reported the final integrated verification as Maven `test-compile`
PASS in 37.678 seconds, 46 classes / 423 tests PASS in 48.832 seconds, and the 45-test harness PASS. That
suite includes exact solver, resource-budget, privacy, loop-cost, native/cost, and exhaustive coverage.

## MemoProgramFacts frozen indexes

The final implementation freezes the builder's guard facts before constructing indexes
(`MemoProgramFacts.java:258-266`). Per-loop lists retain registration order, while the sparse
loop/guard index uses `putIfAbsent` and binary search (`MemoProgramFacts.java:591-606`), preserving the
old stream `findFirst` rule without allocating a dense loop-by-guard table. Canonical guard reuse is
partitioned by syntactic guard and exact frame; it stores provenance and the syntactic `GuardDecision`
together and publishes with `putIfAbsent` (`MemoProgramFacts.java:329-353`). Occurrence contexts validate
the id before caching the immutable constructed list (`MemoProgramFacts.java:452-485`).

The independent `runtime_audit` reviewer approved these properties with high confidence: sorted sparse
per-loop first-entry lookup, immutable exact syntactic guard/frame canonicalization, and immutable
occurrence-context reuse. Its stated low-coverage limits are that the tests do not actually construct and
assert a duplicate `(loop, guard)` pair and do not stress concurrent access or post-freeze mutation.

Validation caveat: the prior isolated 35-test green log predates the final sparse index binary. The sparse
revision initially failed isolated compilation because `java.util.Arrays` was missing; the import is now
present. The leader reported the subsequent integrated Maven `test-compile` PASS in 37.678 seconds. The
historical evidence and its corrected scope are recorded in `facts-implementation.md`.

## MemoOptimizer lookup probe

Approved. Cache lookup borrows the mutable probe only for `Map.get` and clears it in `finally` before any
predicate or cost callback runs (`MemoOptimizer.java:1201-1207`). Misses insert a cloned immutable
`TupleKey` only after evaluation completes (`MemoOptimizer.java:497-503`, `634-643`, `1062-1065`). Probe
and stored keys share the same id, array equality, and hash formula, so equality is symmetric and collision
safe (`MemoOptimizer.java:1208-1236`). Support keys still include the check id, fixed owner/value, and full
revision stamp (`MemoOptimizer.java:455-470`); conditional-bound keys still encode every current domain
value and the cost-term id (`MemoOptimizer.java:1046-1065`). Cache capacities zero and one retain their
existing map semantics.

`MemoOptimizerTest.java:32-46` forces colliding partial and complete truth/cost arrays across cache sizes
0, 1, and 4096 and verifies the exact optimum. Coverage does not explicitly force a collision between two
support revision keys, between two conditional-bound domain keys, or between distinct check/term ids.
Those paths use the same reviewed identity implementation, so this is a focused test gap rather than a
source defect.

## MemoShapes proof reuse

Approved. `SemanticContext` snapshots every value that can change the reviewed proof walks: exact
syntactic and canonical guard fields plus truth, full loop/iteration/cardinality maps, call frame,
`maxDepth`, and every carried slot's `headIsBound` value (`MemoShapes.java:721-765`). Sets and maps make
key equality independent of insertion order, matching lookup semantics. The ordered carried-head snapshot
is deliberately conservative: unrelated head changes may reduce hits, but cannot produce an unsound hit.

Omitting selected rule/layout and selected head layout is sound for this class. Resolver control flow uses
`headIsBound` to choose loop-head traversal (`MemoBindingResolver.java:690-700`); selected rule/layout are
only attached to `ResolvedOrigin` metadata (`MemoBindingResolver.java:779-797`). `originShape` branches on
origin kind/reference/slot/frame and does not inspect those payloads (`MemoShapes.java:1024-1036`).

Binding-guard results are cached only after the complete traversal and retain both the chosen guard and
the `unresolved` bit (`MemoShapes.java:276-295`). Repeat proofs use the binding, exact loop decision, and
full semantic context; no in-progress entry is installed, and a Boolean is published only after the full
FIRST-to-MIDDLE proof returns (`MemoShapes.java:421-470`). Thus cyclic traversal cannot observe an
optimistic partial proof. False results and unresolved binding outcomes are valid deterministic cache
values under the frozen model. The new regression explicitly verifies cached `unresolved=true`
(`MemoShapeProofReuseTest.java:157-177`).

The frozen-model premise holds in the reviewed lifecycle. Constructor-only work queues reach a fixed
point, unresolved shapes are materialized, and the queues drain again before dependency summaries and
proof caches are created (`MemoShapes.java:145-164`, `1342-1366`, `1703-1721`); there is no later analysis
refinement entry point. The existing `phaseShapeCache` also publishes only after a projected binding or
occurrence calculation returns (`MemoShapes.java:1002-1021`, `1141-1147`). Per-proof `PhaseEvaluation`
head values and its `evaluating` recursion sentinel are local to that proof (`MemoShapes.java:830-865`).
Consequently a cached false/UNRESOLVED result cannot capture an in-progress value that would later become
more precise within the same `MemoShapes` instance.

The bounded access-order maps evict after insertion and therefore also implement capacity zero correctly
(`MemoShapes.java:473-478`). Cache-hit traversal counters are returned as zero
(`MemoShapes.java:280-284`), which records avoided physical work; the semantic consumer uses only guard and
unresolved. Reuse request/hit counters are direct physical-cache statistics.

Targeted evidence in `shape-targeted-green.log` is current: 21 tests passed in 10.221 seconds, including
the six proof-reuse fixtures. The fixtures cover semantic parity with caching disabled, phase/cardinality/
depth separation, completed repeat-proof reuse, mutable `headIsBound` snapshots, syntactically distinct
canonical-equal guards, LRU eviction, and cached unresolved results
(`MemoShapeProofReuseTest.java:42-177`). `git diff --check` passed for all reviewed production and test
files.

Remaining limitations:

- A false repeat-proof cache hit is implied by the same Boolean path but is not isolated in its own test.
- A selection lookup with different rule/layout payloads and identical `headIsBound` values is not tested
  explicitly; source inspection establishes that these payloads do not affect `originShape`.
- `MemoShapes` clones HOP views and derives dependency tables during construction
  (`MemoShapes.java:122-164`). Later HOP graph mutation is outside that frozen-analysis lifecycle and is
  not covered by these caches or tests.
- The caches and counters are model-local and unsynchronized. This matches the existing synchronous
  `MemoShapes` work queues and phase cache, but concurrent use is not tested or supported by this review.
- Cache-hit tests do not assert that traversal counters are zero; current callers treat those counters as
  physical work metrics, not logical replay totals.

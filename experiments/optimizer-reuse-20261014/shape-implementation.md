# MemoShapes proof reuse implementation evidence

## Scope

- Production: `MemoShapes.java`
- Regression: `MemoShapeProofReuseTest.java`
- No workload benchmark or full Maven build was run in this lane.

## Behavior lock

Frozen request-dedup engine classes were used before implementation.

```text
MemoLoopCostContextProjectionTest
MemoRepeatedLoopCostTest
MemoCostContextProjectionTest
```

Result: 15 tests passed. Log: `shape-existing-before.log`.

The new regression initially failed compilation against the frozen engine because the bounded-cache
constructor seam and reuse statistics API did not exist. Log: `shape-test-red.log`.

## Implementation

- Added bounded per-`MemoShapes` LRU caches for completed binding-guard queries and completed
  repeat-head cost-stat proofs. Cache size zero provides the reference path used by differential tests.
- Cache keys preserve complete guard syntax and canonical provenance, loop boundaries, iteration phases,
  cardinalities, exact call frame, maximum traversal depth, and every loop-head bound bit.
- Selected rule/layout values are omitted deliberately: resolver carries them in `ResolvedOrigin` metadata,
  while shape derivation ignores those payload fields. `headIsBound` changes traversal and is included.
- Repeat proofs are inserted only after first-to-middle evaluation finishes. No in-progress result is visible.
- Cached binding outcomes retain `unresolved`; per-query traversal counters are zero on reuse so aggregate
  diagnostics continue to represent work actually performed.
- `phaseProvesGuard` now iterates the loop-specific guard-phase index and retains first-fact semantics via
  `MemoProgramFacts.guardValue`.

## Targeted verification

The overlay compiled `MemoShapes.java` and `MemoShapeProofReuseTest.java` against the frozen engine and
then ran the new regression plus the three behavior-lock classes.

Result: 21 tests passed in 10.221 seconds. Log: `shape-targeted-green.log`.

The six new tests cover cache-on/cache-off semantic parity, completed repeat-proof reuse, phase,
cardinality and max-depth separation, mutable `headIsBound` snapshots, syntactic guard frames that share
canonical identity, bounded LRU eviction, and preservation of unresolved unbound dependencies.

Compile log: `shape-compile.log` (warnings only for the required incubating vector module).

## Remaining integration check

The leader owns the clean integrated compile and full suite because `MemoProgramFacts.java` was being
edited concurrently in another lane. This lane did not modify that file.

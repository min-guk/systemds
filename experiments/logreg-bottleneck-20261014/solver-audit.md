# LogReg solver bottleneck audit

## Scope and evidence boundary

This is a read-only source audit of the current `MemoOptimizer`. No workload was run by this lane. The
leader supplied the current non-JFR observation: 29.82 seconds, 2,933,891 search nodes, about 82 million
truth-cache hits, 38 million support-cache misses (`supportQueries`), and 11 million conditional-bound
queries. The leader also reports that about 99.97% of conditional-bound queries hit `boundCache`.

Those counters prove repeated solver work, but do not by themselves assign elapsed time to a method. The
fresh JFR attribution below supplies sampled, overlapping inclusive-frame counts rather than exact method
self time.

## Source findings

### P0 candidate: support cache identity prevents exact restored-state reuse

`propagate` tests every remaining value of every ready check owner and calls `support`
(`MemoOptimizer.java:430-450`). `support` keys its bounded cache by `(checkId, fixedOwner, fixedValue)` plus
the current revision number of each owner in the check scope (`MemoOptimizer.java:455-470`). Each removal
increments an owner revision; undo restores the domain bit but increments the revision again
(`MemoOptimizer.java:1110-1122`). Therefore two visits with exactly equal scoped domain contents after
backtracking necessarily have different revision keys. The cache cannot reuse their equal answer.

This is a source-proven missed-reuse mechanism. Its share of the 38 million misses remains a measurement
question until a content-hit counter or the fresh JFR is available.

The result of `supportDfs` is a deterministic function of the immutable check, fixed owner/value, the
exact domain contents of owners in that check, and the solver-wide fixed `supportEvaluations` budget
(`MemoOptimizer.java:474-494`). Its variable choice uses cardinality and stable check-owner order, so the
same scoped contents also reproduce finite-budget `UNKNOWN`. Domains outside the check scope cannot affect
the result. Check identities/predicates can change during singleton normalization, but that path already
clears `supportCache` (`MemoOptimizer.java:250-286`).

Recommended minimal exact change:

1. Replace the support revision vector with an exact snapshot of each check owner's domain bits.
2. Use a lookup-only probe that borrows the live `BitSet[]` during `Map.get`; on a miss, insert an immutable
   key containing cloned scoped `BitSet`s. Equality must compare every scoped `BitSet` exactly, with the
   check id and fixed owner/value. Hashes are only an accelerator and may never substitute for equality.
3. Preserve the existing bounded capacity, cache all three truth values, and retain the normalization
   clear. Do not widen the key to unrelated owners.

This avoids serializing a variable-length integer array on every lookup and permits equal local states to
hit after undo. It does not prune a value, alter `UNKNOWN`, change the support budget, or change search
order. Stored-key allocation occurs only on a miss.

The simplest implementation needs no separate cardinality field: exact `BitSet` content already encodes
both cardinality and every allowed ordinal. A small lookup/stored-key identity interface can expose the
check id, fixed pair, scoped-domain count, and domain-at-position. The probe maps a position through the
immutable check scope into the live global domain array; the stored key owns one clone per scoped domain.
This avoids eager Cartesian materialization and keeps collision checking exact.

Risks and required tests:

- A mutable probe must never be inserted and must be cleared before `supportDfs` or any predicate runs,
  following the reviewed `TupleProbe` lifetime at `MemoOptimizer.java:1201-1236`.
- Add a deterministic backtracking fixture with an instrumented check showing equal scoped domain content
  reached after undo returns the same status/assignment/cost and reduces predicate/support work.
- Exercise finite support budgets that return `UNKNOWN`; content caching must reproduce rather than turn
  that result into YES/NO.
- Retain randomized exhaustive parity for `OPTIMAL`, `INFEASIBLE`, `UNRESOLVED`, exact `BigDecimal` cost,
  and lexicographic selected rules across cache capacities 0, 1, and the default.
- Force hash collisions between different domain contents and between different fixed owner/value pairs.

### Deferred candidate: allocation-free exact bound-cache lookup

Every dirty multi-owner term recomputation first scans all scoped domains, computes cardinalities, allocates
and fills `keyValues`, and hashes it before the already-hot cache can return a bound
(`MemoOptimizer.java:1029-1065`). The leader's 11 million queries and roughly 99.97% hit rate mean most of
these calls avoid `minimumTerm`, while still paying key construction and domain enumeration. Remove/undo
marks every incident term dirty (`MemoOptimizer.java:1110-1127`), which explains why restored states ask
the cache again.

If a later profile attributes material self time to this path, use the same exact live-domain probe plus
immutable scoped-domain snapshot for `boundCache`. The key is `(term id, exact domain bits for every term
owner)`. On a hit it returns the previously computed exact `BigDecimal`; on a miss the existing
`minimumTerm` and certified-minimum early exit remain unchanged (`MemoOptimizer.java:1040-1077`). This
removes the per-hit `int[]` without changing the objective or lower-bound proof.

Required tests are exhaustive parity across backtracking/cache capacities, a deliberate domain-hash
collision, and exact `BigDecimal` equality. A lookup probe must not survive into `minimumTerm` or a cost
callback. The current evidence defers this change: `computeConditionalCostBound` has 888 inclusive core
samples, but 799 of those also include `evaluateCost`/movement callbacks. Inclusive counts overlap, so the
remaining difference is not exact self time; it does show that attributing all 888 samples to bound-key
construction would be wrong. A 99.97% logical hit rate alone does not prove elapsed-time dominance.

## Lower-priority observations

- Reduced search creates and sorts a boxed value list at every branching node
  (`MemoOptimizer.java:892-930`) although native costs and ordinal tie order are immutable. A precomputed
  per-owner rule order is exact, but should follow only if profiling shows stream/boxing/sort cost.
- Owner selection recomputes all live-neighbor cardinalities per node (`MemoOptimizer.java:904-918`,
  `1079-1101`). Incremental connectivity has broader reversible-state and tie-order risk and is not a
  minimal first change.
- Removing `propagate` stamp checks is not recommended from source review alone. The queue is mutated while
  a check is processed, and the revision comparison protects fixed-point scheduling
  (`MemoOptimizer.java:430-450`). The support-key repair does not require changing that invariant.

## Recommendation order

1. Implement and measure exact content-keyed support reuse first. It repairs a concrete restored-state
   cache-identity mismatch in a measured hot path.
2. Keep the bound lookup probe as a later option only if a follow-up profile isolates its own key overhead.
   It is allocation removal, not stronger pruning.

Both changes preserve exact enumeration, coverage semantics, finite-budget `UNKNOWN`, certified lower
bounds, exact primitive `BigDecimal` arithmetic, and lexicographic tie selection.

## Implemented slice

After the fresh profile, the leader assigned the bounded implementation in `MemoOptimizer.java` and the
new `MemoOptimizerDomainCacheTest.java`.

- Support and conditional-bound caches now share one exact scoped-domain identity implementation. A
  lookup-only probe borrows live `BitSet`s only during `Map.get`; stored keys clone every scoped domain.
  Check and term ids remain in separate maps, and support additionally keys the fixed owner/value.
- Support records `supportCacheHits`. All three truth values remain cacheable, including finite-budget
  `UNKNOWN`; singleton normalization still clears the cache.
- Conditional-bound lookup now precedes combination counting. A miss executes the unchanged budget check,
  certified-minimum wide relaxation, and exact `minimumTerm`; wide results remain unstored. An exact hit
  therefore avoids both cardinality scanning and key serialization without changing the lower bound.
- `domainCardinalities` is initialized from immutable domain sizes and updated only in the two domain
mutation paths, `remove` and `undo`. All previous `BitSet.cardinality()` decisions now read that exact
reversible count; assertions lock bit presence and equality with the actual `BitSet` cardinality after
both mutation directions without changing branch order.

Behavior was locked first against the frozen pre-change engine: the three new functional fixtures plus
`MemoOptimizerTest` and `MemoExhaustiveParityTest` passed 30 tests. The reuse-counter assertion then failed
to compile against the frozen API as expected. After implementation, isolated production and test
compilation passed, and the same 30 tests passed with assertions enabled in 1.667 seconds. The fixtures
cover cache capacities 0/1/4096, recreated local domains after backtracking, support and bound hits,
finite support-budget parity, exact status/cost/selected-rule parity, lexicographic ties, equal
`wideBoundRelaxations`, and distinct domains with colliding Java `BitSet` hashes. Logs are
`domain-cache-before-junit.log`, `domain-cache-reuse-red-javac.log`, and
`domain-cache-after-junit-06.log`. `git diff --check` passed.

No runtime speedup is claimed here; the leader owns the coordinated build, full suite, and single allowed
after measurement.

Coordinated verification subsequently passed: clean Maven `test-compile` in 42.981 seconds, 47-class
integrated suite with 426 tests in 47.326 seconds, and the 45-test harness. The independent reviewer
approved the frozen change for the single AFTER measurement and separately passed the new domain-cache
test plus `MemoOptimizerTest` with assertions enabled (25 tests). The final implementer assertion-enabled
run is `domain-cache-after-junit-06.log` (30 tests).

## Fresh profile attribution

The runtime agent's fresh aggregate reports 2,599 reduced-core execution samples. Inclusive counts include
1,011 in `propagate` and 916 in `support` (35.2% of core samples), making the content-key repair the strongest
measured solver candidate. `coreLowerBound` has 1,042 samples, `currentTermBound` 895, and
`computeConditionalCostBound` 888, but 799 also include `evaluateCost` and movement callbacks; these
overlapping counts do not establish bound-key construction as the cause. Other observed inclusive frames
include `BitSet.cardinality` 264, `HashMap.get` 245, `Arrays.hashCode` 137, `BitSet.set` 127, and `stamp` 76.
Reduced-core sampled allocation weight is 35.13 GiB.

The profile confirms support/propagation as material. It does not yet quantify how many of the 38 million
support misses become content hits, so the expected speedup remains unclaimed until the before/after run.
